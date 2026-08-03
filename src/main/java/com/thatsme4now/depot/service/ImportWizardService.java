package com.thatsme4now.depot.service;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.thatsme4now.depot.controller.DepotRestController.MappedRow;
import com.thatsme4now.depot.dto.ImportHistoryDTO;
import com.thatsme4now.depot.dto.ImportStagingRowDTO;
import com.thatsme4now.depot.entity.CurrentPrice;
import com.thatsme4now.depot.entity.ImportHistory;
import com.thatsme4now.depot.entity.ImportStagingRow;
import com.thatsme4now.depot.entity.Position;
import com.thatsme4now.depot.entity.Transaction;
import com.thatsme4now.depot.entity.TransactionType;
import com.thatsme4now.depot.repository.ImportHistoryRepository;
import com.thatsme4now.depot.repository.ImportStagingRowRepository;
import com.thatsme4now.depot.repository.TransactionRepository;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Backend für den 3-Step-Import-Assistenten (Mapping-Vorschau → Review →
 * Status). Ersetzt für den normalen CSV-Import NICHT die bestehende
 * {@link CsvImportService} (die bleibt für .enc-Importe und als Basis für
 * Datums-Parsing/Positions-Resolution im Einsatz), sondern ergänzt sie um
 * eine fehlertolerante Staging-Stufe: Zeilen, die nicht sauber gemappt
 * werden konnten, verschwinden nicht mehr still, sondern landen sichtbar
 * (mit Fehlergrund) in der import_staging_row-Tabelle und können dort vom
 * Nutzer repariert oder gelöscht werden, bevor final importiert wird.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportWizardService {

    private final ImportStagingRowRepository stagingRepo;
    private final ImportHistoryRepository    historyRepo;
    private final TransactionRepository      transactionRepo;
    private final DepotService               depotService;
    private final CsvImportService           csvImportService;

    // ── Step 1: Datei-Upload → Header/Rohzeilen parsen ─────────────────────────

    /**
     * Parst die hochgeladene CSV-Datei serverseitig (ersetzt PapaParse im
     * Browser für diesen Schritt) und liefert Header + Rohzeilen für die
     * Mapping-Seite. Leert defensiv die Staging-Tabelle, falls von einem
     * abgebrochenen vorherigen Import noch Datenreste vorhanden sind.
     */
    @Transactional
    public UploadResult parseUpload(MultipartFile file) throws IOException {
        stagingRepo.deleteAllInBatch();

        byte[] bytes = file.getBytes();
        int offset = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            offset = 3; // UTF-8 BOM
        }
        String content = new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8);
        char delimiter = detectDelimiter(content);

        List<List<String>> allRows = new ArrayList<>();
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setTrim(true)
                .setIgnoreEmptyLines(true)
                .build()
                .parse(new StringReader(content))) {
            for (CSVRecord rec : parser) {
                List<String> vals = new ArrayList<>();
                rec.forEach(vals::add);
                allRows.add(vals);
            }
        }

        UploadResult result = new UploadResult();
        result.filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "import.csv";

        if (allRows.size() < 2) {
            result.headers = List.of();
            result.rows = List.of();
            return result;
        }

        // Header-Zeile, doppelte Namen mit Suffix versehen — exakt wie bisher
        // clientseitig in openMappingModal() (depot.js).
        List<String> rawHeaders = allRows.get(0);
        Map<String, Integer> seen = new HashMap<>();
        List<String> headers = new ArrayList<>();
        for (String h : rawHeaders) {
            String key = h == null ? "" : h.trim();
            Integer count = seen.get(key);
            if (count == null) {
                seen.put(key, 0);
                headers.add(key);
            } else {
                count++;
                seen.put(key, count);
                headers.add(key + "_" + count);
            }
        }

        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < allRows.size(); i++) {
            List<String> vals = allRows.get(i);
            Map<String, String> obj = new LinkedHashMap<>();
            for (int c = 0; c < headers.size(); c++) {
                obj.put(headers.get(c), c < vals.size() ? vals.get(c) : "");
            }
            rows.add(obj);
        }

        result.headers = headers;
        result.rows = rows;
        return result;
    }

    /**
     * Erkennt das CSV-Trennzeichen anhand der ersten (Header-)Zeile, da
     * CSVFormat.DEFAULT fest auf Komma steht. Exportformate wie CoinTracking
     * nutzen Komma, viele Wallet-/Hardware-Wallet-Exporte dagegen Semikolon
     * oder Tab — ohne diese Erkennung landet die gesamte Header-Zeile als ein
     * einziger Spaltenname und das Mapping kann keine Felder mehr zuordnen
     * (vorher hat clientseitiges PapaParse den Delimiter automatisch erkannt).
     */
    private char detectDelimiter(String content) {
        int firstLineEnd = content.indexOf('\n');
        String firstLine = firstLineEnd >= 0 ? content.substring(0, firstLineEnd) : content;
        firstLine = firstLine.replace("\r", "");

        char[] candidates = { ',', ';', '\t' };
        char best = ',';
        int bestCount = -1;
        for (char c : candidates) {
            int count = 0;
            for (int i = 0; i < firstLine.length(); i++) {
                if (firstLine.charAt(i) == c) count++;
            }
            if (count > bestCount) {
                bestCount = count;
                best = c;
            }
        }
        return bestCount > 0 ? best : ',';
    }

    // ── Step 1 → Step 2: gemappte Zeilen in die Staging-Tabelle übernehmen ────

    @Transactional
    public StageResult stageRows(List<MappedRow> mappedRows, String displayCurrency) {
        stagingRepo.deleteAllInBatch(); // defensiv, falls doppelt aufgerufen

        List<ImportStagingRow> built = new ArrayList<>();
        int skippedNoBtc = 0;
        if (mappedRows != null) {
            for (MappedRow r : mappedRows) {
                try {
                    built.addAll("Selbst".equals(r.getTyp()) ? buildSelfRows(r) : buildTradeRows(r));
                } catch (NoBtcTradeException e) {
                    skippedNoBtc++;
                } catch (Exception e) {
                    log.warn("Skipping row during staging: {}", e.getMessage());
                }
            }
        }

        // Chronologische Reihenfolge (Dateien sind i.d.R. neueste-zuerst) — wie
        // im bestehenden Mapped-Import (csvRows.reversed()) — nötig für das
        // Transfer-Pairing unten.
        Collections.reverse(built);
        for (int i = 0; i < built.size(); i++) {
            built.get(i).setRowIndex(i);
        }

        assignTransferIds(built);
        markDuplicates(built);
        markFxWarnings(built, displayCurrency);

        stagingRepo.saveAll(built);

        StageResult result = new StageResult();
        result.staged = built.size();
        result.errorCount = (int) built.stream().filter(ImportStagingRow::isHasError).count();
        result.skippedNoBtc = skippedNoBtc;
        return result;
    }

    /** Signalisiert, dass eine Zeile (Trade/Einzahlung/Auszahlung) ohne BTC-Bezug
     *  beim Staging übersprungen werden soll, statt als Fehler angezeigt zu werden. */
    private static class NoBtcTradeException extends RuntimeException {
    }

    private List<ImportStagingRow> buildTradeRows(MappedRow r) {
        ImportStagingRow row = new ImportStagingRow();
        row.setRawTyp(r.getTyp());
        row.setPositionLabel(blankToNull(r.getExchange()));

        LocalDateTime dateTime = parseDate(r.getDate());
        row.setDateRaw(r.getDate());
        row.setDateParsed(dateTime);

        BigDecimal buyQty  = decimal(r.getBuyQuantity());
        BigDecimal sellQty = decimal(r.getSellQuantity());
        BigDecimal fee     = decimal(r.getFee());
        BigDecimal exRate  = decimal(r.getExchangeRate());
        String buyCur  = blankToNull(r.getBuyCurrency());
        String sellCur = blankToNull(r.getSellCurrency());

        row.setFees(fee);
        row.setFeesCurrency(blankToNull(r.getFeeCurrency()));
        row.setComment(blankToNull(r.getComment()));
        row.setTransactionId(blankToNull(r.getTransactionId()));
        row.setTransferId(blankToNull(r.getTransferId()));
        row.setExchangeRate(exRate != null && exRate.compareTo(BigDecimal.ZERO) > 0 ? exRate : BigDecimal.ONE);

        List<String> errors = new ArrayList<>();
        if (dateTime == null) errors.add("invalid_date");
        if (row.getPositionLabel() == null) errors.add("missing_exchange");

        TransactionType txType = null;
        BigDecimal quantity = null;
        BigDecimal quantityFiat = null;
        BigDecimal pricePerBtc = null;
        String currency = null;

        String typ = r.getTyp() == null ? "" : r.getTyp().trim();
        switch (typ) {
            case "Trade" -> {
                if ("BTC".equalsIgnoreCase(buyCur)) {
                    txType = TransactionType.BUY;
                    quantity = buyQty;
                    quantityFiat = sellQty;
                    pricePerBtc = (buyQty != null && buyQty.compareTo(BigDecimal.ZERO) > 0 && sellQty != null)
                        ? sellQty.divide(buyQty, 2, RoundingMode.HALF_UP) : null;
                    currency = sellCur != null ? sellCur : "EUR";
                } else if ("BTC".equalsIgnoreCase(sellCur)) {
                    txType = TransactionType.SELL;
                    quantity = sellQty;
                    quantityFiat = buyQty;
                    pricePerBtc = (sellQty != null && sellQty.compareTo(BigDecimal.ZERO) > 0 && buyQty != null)
                        ? buyQty.divide(sellQty, 2, RoundingMode.HALF_UP) : null;
                    currency = buyCur != null ? buyCur : "EUR";
                } else {
                    // Weder Kauf- noch Verkaufswährung ist BTC -> kein BTC-Bezug,
                    // Zeile wird nicht gestaged, sondern in stageRows() gezählt.
                    throw new NoBtcTradeException();
                }
            }
            case "Einzahlung" -> {
                if ("BTC".equalsIgnoreCase(buyCur)) {
                    txType = TransactionType.TRANSFER_IN;
                    quantity = buyQty;
                } else {
                    // Einzahlung einer Nicht-BTC-Währung -> kein BTC-Bezug, überspringen.
                    throw new NoBtcTradeException();
                }
            }
            case "Auszahlung" -> {
                if ("BTC".equalsIgnoreCase(sellCur)) {
                    txType = TransactionType.TRANSFER_OUT;
                    quantity = sellQty;
                } else {
                    // Auszahlung einer Nicht-BTC-Währung -> kein BTC-Bezug, überspringen.
                    throw new NoBtcTradeException();
                }
            }
            default -> errors.add("unknown_type");
        }

        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            errors.add("invalid_quantity");
        }

        row.setType(txType);
        row.setQuantity(quantity);
        row.setQuantityFiat(quantityFiat);
        row.setPricePerBtc(pricePerBtc);
        row.setCurrency(currency);

        if (!errors.isEmpty()) {
            row.setHasError(true);
            row.setErrorReason(String.join(",", errors));
        }
        return List.of(row);
    }

    /** SELF-Zeilen (Wallet-Export): Abgang mit Netzwerk-Fee + Zugang auf derselben
     *  Position, gepaart über eine gemeinsame transferId — analog CsvImportService#mapSelfRows,
     *  aber fehlertolerant (fehlende Werte führen zu hasError statt stillem Verwerfen). */
    private List<ImportStagingRow> buildSelfRows(MappedRow r) {
        String exchange = blankToNull(r.getExchange());
        LocalDateTime dateTime = parseDate(r.getDate());

        BigDecimal amount = decimal(r.getBuyQuantity());
        if (amount == null) amount = decimal(r.getSellQuantity());
        BigDecimal fee = decimal(r.getFee());
        if (fee == null) fee = BigDecimal.ZERO;

        BigDecimal inQuantity = null;
        if (amount != null) {
            inQuantity = amount.subtract(fee);
            if (inQuantity.compareTo(BigDecimal.ZERO) <= 0) inQuantity = amount;
        }

        String feeCurrency = r.getFeeCurrency() != null && !r.getFeeCurrency().isBlank()
            ? r.getFeeCurrency().trim() : "BTC";
        String comment = blankToNull(r.getComment());
        String transferId = UUID.randomUUID().toString();
        String baseTxId = blankToNull(r.getTransactionId());

        List<String> errors = new ArrayList<>();
        if (dateTime == null) errors.add("invalid_date");
        if (exchange == null) errors.add("missing_exchange");
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) errors.add("invalid_quantity");
        boolean hasError = !errors.isEmpty();
        String reason = hasError ? String.join(",", errors) : null;

        ImportStagingRow out = new ImportStagingRow();
        out.setRawTyp(r.getTyp());
        out.setPositionLabel(exchange);
        out.setDateRaw(r.getDate());
        out.setDateParsed(dateTime);
        out.setType(TransactionType.TRANSFER_OUT);
        out.setQuantity(amount);
        out.setFees(fee);
        out.setFeesCurrency(feeCurrency);
        out.setComment(comment);
        out.setTransferId(transferId);
        out.setExchangeRate(BigDecimal.ONE);
        out.setTransactionId(baseTxId != null ? baseTxId + "-out" : null);
        out.setHasError(hasError);
        out.setErrorReason(reason);

        ImportStagingRow in = new ImportStagingRow();
        in.setRawTyp(r.getTyp());
        in.setPositionLabel(exchange);
        in.setDateRaw(r.getDate());
        in.setDateParsed(dateTime);
        in.setType(TransactionType.TRANSFER_IN);
        in.setQuantity(inQuantity);
        in.setComment(comment);
        in.setTransferId(transferId);
        in.setExchangeRate(BigDecimal.ONE);
        in.setTransactionId(baseTxId != null ? baseTxId + "-in" : null);
        in.setHasError(hasError);
        in.setErrorReason(reason);

        List<ImportStagingRow> pair = new ArrayList<>();
        pair.add(in);
        pair.add(out);
        return pair;
    }

    /** Automatisches Transfer-Pairing — Portierung von CsvImportService#assignTransferIds
     *  (4er-Lookahead-Fenster, Mengen-/Gebühren-Toleranz) auf ImportStagingRow. */
    private void assignTransferIds(List<ImportStagingRow> rows) {
        for (int i = 0; i < rows.size() - 1; i++) {
            ImportStagingRow curr = rows.get(i);
            if (curr.getType() != TransactionType.TRANSFER_OUT
                    || curr.getTransferId() != null
                    || curr.getQuantity() == null) continue;

            for (int lookahead = 1; lookahead <= 4 && i + lookahead < rows.size(); lookahead++) {
                ImportStagingRow candidate = rows.get(i + lookahead);
                if (candidate.getType() == TransactionType.TRANSFER_IN
                        && candidate.getTransferId() == null
                        && candidate.getQuantity() != null
                        && (curr.getQuantity().compareTo(candidate.getQuantity()) == 0
                            || (curr.getFees() != null
                                && curr.getQuantity().subtract(curr.getFees()).compareTo(candidate.getQuantity()) == 0))) {
                    String uuid = UUID.randomUUID().toString();
                    curr.setTransferId(uuid);
                    candidate.setTransferId(uuid);
                    break;
                }
            }
        }
    }

    /** Duplikat-Kriterium: Datum+Typ+Menge, gegen die bestehende DB UND innerhalb
     *  des neuen Batches selbst (siehe Absprache) — Zeilen mit transactionId werden
     *  nie über dieses Kriterium markiert. */
    private void markDuplicates(List<ImportStagingRow> rows) {
        Map<String, List<ImportStagingRow>> byKey = new HashMap<>();
        for (ImportStagingRow row : rows) {
            if (row.getTransactionId() != null) continue;
            if (row.getDateParsed() == null || row.getType() == null || row.getQuantity() == null) continue;
            String key = row.getDateParsed() + "|" + row.getType() + "|"
                + row.getQuantity().setScale(8, RoundingMode.HALF_UP).toPlainString();
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        for (List<ImportStagingRow> group : byKey.values()) {
            ImportStagingRow sample = group.get(0);
            boolean dbDup = transactionRepo.existsByDateAndTypeAndQuantity(
                sample.getDateParsed(), sample.getType(), sample.getQuantity());
            if (group.size() > 1 || dbDup) {
                group.forEach(r -> r.setDuplicate(true));
            }
        }
    }

    /** FX-Kriterium: currency weicht von der aktuell gewählten Oberflächenwährung
     *  ab UND exchangeRate fehlt/=1 — nur bei BUY/SELL relevant (siehe Absprache). */
    private void markFxWarnings(List<ImportStagingRow> rows, String displayCurrency) {
        String display = (displayCurrency == null || displayCurrency.isBlank()) ? "EUR" : displayCurrency.toUpperCase();
        for (ImportStagingRow row : rows) {
            if (row.getType() != TransactionType.BUY && row.getType() != TransactionType.SELL) continue;
            if (row.getCurrency() == null) continue;
            boolean differentCurrency = !row.getCurrency().equalsIgnoreCase(display);
            boolean noRate = row.getExchangeRate() == null || row.getExchangeRate().compareTo(BigDecimal.ONE) == 0;
            if (differentCurrency && noRate) row.setFxWarning(true);
        }
    }

    // ── Step 2: Review — Staging-CRUD ──────────────────────────────────────────

    public List<ImportStagingRowDTO> listStaging() {
        return stagingRepo.findAllByOrderByRowIndexAsc().stream().map(this::toDTO).toList();
    }

    @Transactional
    public ImportStagingRowDTO updateStaging(Long id, StagingUpdateRequest req, String displayCurrency) {
        ImportStagingRow row = stagingRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Staging row not found: " + id));

        if (req.getDate() != null) {
            row.setDateRaw(req.getDate());
            row.setDateParsed(parseDate(req.getDate()));
        }
        if (req.getType() != null) row.setType(req.getType());
        if (req.getQuantity() != null) row.setQuantity(req.getQuantity());
        row.setQuantityFiat(req.getQuantityFiat());
        if (req.getCurrency() != null) row.setCurrency(blankToNull(req.getCurrency()));
        if (req.getExchangeRate() != null) row.setExchangeRate(req.getExchangeRate());
        row.setFees(req.getFees());
        row.setFeesCurrency(req.getFeesCurrency());
        if (req.getExchange() != null) row.setPositionLabel(blankToNull(req.getExchange()));
        row.setComment(req.getComment());
        if (req.getTransferId() != null) {
            row.setTransferId(req.getTransferId().isBlank() ? null : req.getTransferId().trim());
        }

        List<String> errors = new ArrayList<>();
        if (row.getDateParsed() == null) errors.add("invalid_date");
        if (row.getPositionLabel() == null) errors.add("missing_exchange");
        if (row.getType() == null) errors.add("unknown_type");
        if (row.getQuantity() == null || row.getQuantity().compareTo(BigDecimal.ZERO) <= 0) errors.add("invalid_quantity");
        row.setHasError(!errors.isEmpty());
        row.setErrorReason(errors.isEmpty() ? null : String.join(",", errors));

        recomputeDuplicateForRow(row);
        recomputeFxForRow(row, displayCurrency);

        stagingRepo.save(row);
        return toDTO(row);
    }

    private void recomputeDuplicateForRow(ImportStagingRow row) {
        if (row.getTransactionId() != null || row.getDateParsed() == null
                || row.getType() == null || row.getQuantity() == null) {
            row.setDuplicate(false);
            return;
        }
        boolean dbDup = transactionRepo.existsByDateAndTypeAndQuantity(
            row.getDateParsed(), row.getType(), row.getQuantity());
        boolean batchDup = stagingRepo.countByDateParsedAndTypeAndQuantityAndIdNot(
            row.getDateParsed(), row.getType(), row.getQuantity(), row.getId()) > 0;
        row.setDuplicate(dbDup || batchDup);
    }

    private void recomputeFxForRow(ImportStagingRow row, String displayCurrency) {
        String display = (displayCurrency == null || displayCurrency.isBlank()) ? "EUR" : displayCurrency.toUpperCase();
        boolean isTrade = row.getType() == TransactionType.BUY || row.getType() == TransactionType.SELL;
        boolean differentCurrency = isTrade && row.getCurrency() != null && !row.getCurrency().equalsIgnoreCase(display);
        boolean noRate = row.getExchangeRate() == null || row.getExchangeRate().compareTo(BigDecimal.ONE) == 0;
        row.setFxWarning(isTrade && differentCurrency && noRate);
    }

    @Transactional
    public void deleteStaging(Long id) {
        stagingRepo.deleteById(id);
    }

    @Transactional
    public int bulkPairStaging(List<Long> ids) {
        int paired = 0;
        for (int i = 0; i + 1 < ids.size(); i += 2) {
            String uuid = UUID.randomUUID().toString();
            for (int j = i; j < i + 2; j++) {
                stagingRepo.findById(ids.get(j)).ifPresent(row -> {
                    row.setTransferId(uuid);
                    stagingRepo.save(row);
                });
            }
            paired += 2;
        }
        return paired;
    }

    @Transactional
    public int bulkRemoveTransferStaging(List<Long> ids) {
        int removed = 0;
        for (Long id : ids) {
            ImportStagingRow row = stagingRepo.findById(id).orElse(null);
            if (row == null || row.getTransferId() == null) continue;
            row.setTransferId(null);
            stagingRepo.save(row);
            removed++;
        }
        return removed;
    }

    /** Setzt den Wechselkurs für mehrere Staging-Zeilen auf einmal und
     *  rechnet die FX-Warnung je Zeile neu (analog Einzel-Edit). */
    @Transactional
    public int bulkExRateStaging(List<Long> ids, BigDecimal exchangeRate, String displayCurrency) {
        int updated = 0;
        for (Long id : ids) {
            ImportStagingRow row = stagingRepo.findById(id).orElse(null);
            if (row == null) continue;
            row.setExchangeRate(exchangeRate);
            recomputeFxForRow(row, displayCurrency);
            stagingRepo.save(row);
            updated++;
        }
        return updated;
    }

    /** Löscht mehrere Staging-Zeilen auf einmal endgültig. */
    @Transactional
    public int bulkDeleteStaging(List<Long> ids) {
        List<Long> existing = ids.stream().filter(stagingRepo::existsById).toList();
        stagingRepo.deleteAllByIdInBatch(existing);
        return existing.size();
    }

    /** Setzt Wallet/Börse für mehrere Staging-Zeilen auf einmal (reines Label,
     *  keine Position-Erstellung — die passiert wie beim Einzel-Edit erst beim
     *  finalen Commit über {@link CsvImportService#resolvePosition}). Rechnet
     *  den missing_exchange-Fehler je Zeile neu. */
    @Transactional
    public int bulkMoveStaging(List<Long> ids, String targetExchange) {
        String label = blankToNull(targetExchange);
        int updated = 0;
        for (Long id : ids) {
            ImportStagingRow row = stagingRepo.findById(id).orElse(null);
            if (row == null) continue;
            row.setPositionLabel(label);

            List<String> errors = new ArrayList<>();
            if (row.getDateParsed() == null) errors.add("invalid_date");
            if (row.getPositionLabel() == null) errors.add("missing_exchange");
            if (row.getType() == null) errors.add("unknown_type");
            if (row.getQuantity() == null || row.getQuantity().compareTo(BigDecimal.ZERO) <= 0) errors.add("invalid_quantity");
            row.setHasError(!errors.isEmpty());
            row.setErrorReason(errors.isEmpty() ? null : String.join(",", errors));

            stagingRepo.save(row);
            updated++;
        }
        return updated;
    }

    /** Markiert mehrere Staging-Zeilen als bewusst einseitigen Transfer:
     *  jede Zeile bekommt eine eigene, frische Transfer-ID (kein gemeinsames
     *  Pairing mehr) — analog zu {@code DepotRestController#bulkSoloTransfer}
     *  für bereits gebuchte Transaktionen. Nur für TRANSFER_IN/TRANSFER_OUT. */
    @Transactional
    public int bulkSoloTransferStaging(List<Long> ids) {
        for (Long id : ids) {
            ImportStagingRow row = stagingRepo.findById(id).orElse(null);
            if (row != null && row.getType() != TransactionType.TRANSFER_IN
                    && row.getType() != TransactionType.TRANSFER_OUT) {
                throw new IllegalArgumentException("Only TRANSFER_IN and TRANSFER_OUT allowed");
            }
        }
        int marked = 0;
        for (Long id : ids) {
            ImportStagingRow row = stagingRepo.findById(id).orElse(null);
            if (row == null) continue;
            row.setTransferId(UUID.randomUUID().toString());
            stagingRepo.save(row);
            marked++;
        }
        return marked;
    }

    @Transactional
    public void cancelImport() {
        stagingRepo.deleteAllInBatch();
    }

    // ── Step 2 → Step 3: finaler Commit ────────────────────────────────────────

    @Transactional
    public ConfirmResult confirmImport(String filename, Integer totalRowsHint) {
        List<ImportStagingRow> rows = stagingRepo.findAllByOrderByRowIndexAsc();

        seedCurrentPriceFromStaging(rows);

        // History-Eintrag wird VOR der Zeilen-Schleife angelegt (mit
        // Platzhalter-Zählern), damit seine ID für die neue
        // transaction.import_history_id-Verknüpfung schon feststeht. Die
        // Zählerfelder werden nach der Schleife nachgetragen (zweites Save).
        ImportHistory history = new ImportHistory();
        history.setFilename(filename != null && !filename.isBlank() ? filename : "import.csv");
        history.setTotalRows(totalRowsHint != null ? totalRowsHint : rows.size());
        historyRepo.save(history);

        ConfirmResult result = new ConfirmResult();
        int imported = 0, duplicates = 0, errorCount = 0;

        for (ImportStagingRow row : rows) {
            if (row.isHasError()) {
                errorCount++;
                result.errors.add(issue(row, "invalid_data"));
                continue;
            }
            if (row.getTransactionId() != null && transactionRepo.existsByTransactionId(row.getTransactionId())) {
                errorCount++;
                result.errors.add(issue(row, "transaction_id_exists"));
                continue;
            }
            try {
                Position position = csvImportService.resolvePosition(row.getPositionLabel());
                Transaction tx = new Transaction();
                tx.setPosition(position);
                tx.setType(row.getType());
                tx.setDate(row.getDateParsed());
                tx.setQuantity(row.getQuantity());
                tx.setPricePerBtc(row.getPricePerBtc());
                tx.setFees(row.getFees());
                tx.setFeesCurrency(row.getFeesCurrency());
                tx.setQuantityFiat(row.getQuantityFiat());
                tx.setCurrency(row.getCurrency() != null ? row.getCurrency() : "EUR");
                tx.setExchangeRate(row.getExchangeRate() != null ? row.getExchangeRate() : BigDecimal.ONE);
                tx.setTransferId(row.getTransferId());
                tx.setComment(row.getComment());
                tx.setTransactionId(row.getTransactionId() != null ? row.getTransactionId() : UUID.randomUUID().toString());
                tx.setDuplicate(row.isDuplicate());
                tx.setImportHistoryId(history.getId());
                transactionRepo.save(tx);
                imported++;
                if (row.isDuplicate()) duplicates++;
            } catch (Exception e) {
                log.warn("Failed to import staging row {}: {}", row.getId(), e.getMessage());
                errorCount++;
                result.errors.add(issue(row, "save_failed"));
            }
        }

        history.setImportedRows(imported);
        history.setDuplicateRows(duplicates);
        history.setErrorRows(errorCount);
        historyRepo.save(history);

        stagingRepo.deleteAllInBatch();

        result.historyId = history.getId();
        result.totalRows = history.getTotalRows();
        result.importedRows = imported;
        result.duplicateRows = duplicates;
        result.errorRows = errorCount;
        return result;
    }

    private void seedCurrentPriceFromStaging(List<ImportStagingRow> rows) {
        ImportStagingRow lastPriceRow = rows.stream()
            .filter(r -> r.getPricePerBtc() != null)
            .max(Comparator.comparing(ImportStagingRow::getRowIndex))
            .orElse(null);

        String currency = lastPriceRow != null && lastPriceRow.getCurrency() != null
            ? lastPriceRow.getCurrency().toUpperCase() : "EUR";
        CurrentPrice cp = depotService.getCurrentPrice(currency).orElse(new CurrentPrice());
        if (cp.getPrice() == null || cp.getPrice().intValue() == 0) {
            cp.setTicker("BTC");
            cp.setCurrency(currency);
            cp.setPrice(lastPriceRow != null ? lastPriceRow.getPricePerBtc() : new BigDecimal(50000));
            cp.setPriceDate(lastPriceRow != null && lastPriceRow.getDateParsed() != null
                ? lastPriceRow.getDateParsed().toLocalDate() : LocalDate.now());
            cp.setLoadedAt(LocalDateTime.now());
            depotService.saveCurrentPrice(cp);
        }
    }

    private ImportIssue issue(ImportStagingRow row, String reason) {
        ImportIssue i = new ImportIssue();
        i.rowIndex = row.getRowIndex();
        i.date = row.getDateRaw();
        i.type = row.getType() != null ? row.getType().name() : null;
        i.positionLabel = row.getPositionLabel();
        i.reason = row.getErrorReason() != null ? row.getErrorReason() : reason;
        return i;
    }

    // ── Historie ────────────────────────────────────────────────────────────

    public List<ImportHistoryDTO> getHistory(int limit) {
        return historyRepo.findAllByOrderByImportedAtDesc(PageRequest.of(0, Math.max(1, limit))).stream()
            .map(this::toDTO)
            .toList();
    }

    /** Löscht einen Import-Historie-Eintrag. Bei {@code deleteTransactions=false}
     *  bleiben die verknüpften Transaktionen erhalten (Verknüpfung wird nur
     *  aufgelöst); bei {@code true} werden sie mitgelöscht. Wirft, falls der
     *  Eintrag nicht existiert. */
    @Transactional
    public void deleteHistory(Long id, boolean deleteTransactions) {
        if (!historyRepo.existsById(id)) {
            throw new IllegalArgumentException("Import history entry not found: " + id);
        }
        if (deleteTransactions) {
            transactionRepo.deleteByImportHistoryId(id);
        } else {
            transactionRepo.clearImportHistoryId(id);
        }
        historyRepo.deleteById(id);
    }

    // ── Mapping-Helfer ──────────────────────────────────────────────────────

    private LocalDateTime parseDate(String date) {
        if (date == null || date.isBlank()) return null;
        return csvImportService.getLocalDateTimeByString(date.trim());
    }

    private BigDecimal decimal(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return new BigDecimal(v.trim().replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String blankToNull(String v) {
        if (v == null) return null;
        String trimmed = v.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ── DTO-Mapping ─────────────────────────────────────────────────────────

    private ImportStagingRowDTO toDTO(ImportStagingRow row) {
        ImportStagingRowDTO dto = new ImportStagingRowDTO();
        dto.setId(row.getId());
        dto.setRowIndex(row.getRowIndex());
        dto.setRawTyp(row.getRawTyp());
        dto.setType(row.getType());
        dto.setPositionLabel(row.getPositionLabel());
        dto.setDateRaw(row.getDateRaw());
        dto.setDateParsed(row.getDateParsed());
        dto.setQuantity(row.getQuantity());
        dto.setQuantityFiat(row.getQuantityFiat());
        dto.setPricePerBtc(row.getPricePerBtc());
        dto.setCurrency(row.getCurrency());
        dto.setExchangeRate(row.getExchangeRate());
        dto.setFees(row.getFees());
        dto.setFeesCurrency(row.getFeesCurrency());
        dto.setComment(row.getComment());
        dto.setTransactionId(row.getTransactionId());
        dto.setTransferId(row.getTransferId());
        dto.setDuplicate(row.isDuplicate());
        dto.setFxWarning(row.isFxWarning());
        dto.setHasError(row.isHasError());
        dto.setErrorReason(row.getErrorReason());
        return dto;
    }

    private ImportHistoryDTO toDTO(ImportHistory h) {
        ImportHistoryDTO dto = new ImportHistoryDTO();
        dto.setId(h.getId());
        dto.setImportedAt(h.getImportedAt());
        dto.setFilename(h.getFilename());
        dto.setTotalRows(h.getTotalRows());
        dto.setImportedRows(h.getImportedRows());
        dto.setDuplicateRows(h.getDuplicateRows());
        dto.setErrorRows(h.getErrorRows());
        dto.setLinkedTransactionCount(transactionRepo.countByImportHistoryId(h.getId()));
        return dto;
    }

    // ── Interne Transport-Klassen ──────────────────────────────────────────

    public static class UploadResult {
        public String filename;
        public List<String> headers;
        public List<Map<String, String>> rows;
    }

    public static class StageResult {
        public int staged;
        public int errorCount;
        public int skippedNoBtc;
    }

    public static class ConfirmResult {
        public Long historyId;
        public int totalRows;
        public int importedRows;
        public int duplicateRows;
        public int errorRows;
        public List<ImportIssue> errors = new ArrayList<>();
    }

    public static class ImportIssue {
        public Integer rowIndex;
        public String date;
        public String type;
        public String positionLabel;
        public String reason;
    }

    @Data
    public static class StagingUpdateRequest {
        private String date;
        private TransactionType type;
        private BigDecimal quantity;
        private BigDecimal quantityFiat;
        private BigDecimal fees;
        private String feesCurrency;
        private String currency;
        private BigDecimal exchangeRate;
        private String comment;
        private String exchange;
        private String transferId;
    }
}
