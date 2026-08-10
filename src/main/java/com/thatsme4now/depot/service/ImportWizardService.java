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
 * Backend for the 3-step import wizard (mapping preview -> review -> status).
 * Does NOT replace the existing {@link CsvImportService} for normal CSV
 * imports (that stays in use for date parsing / position resolution), but
 * adds an error-tolerant staging stage on top of it: rows that couldn't be
 * mapped cleanly no longer silently disappear — they show up (with an error
 * reason) in the import_staging_row table where the user can fix or delete
 * them before the final import.
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

    // ── Step 1: file upload -> parse header/raw rows ─────────────────────────

    /**
     * Parses the uploaded CSV file server-side (replacing the browser's PapaParse
     * for this step) and returns headers + raw rows for the mapping page.
     * Defensively clears the staging table in case a previous, aborted import
     * left data behind.
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

        // Header row, suffixing duplicate names — exactly matching the previous
        // client-side logic in openMappingModal() (depot.js).
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
     * Detects the CSV delimiter from the first (header) line, since
     * CSVFormat.DEFAULT is hardcoded to comma. Export formats like CoinTracking
     * use commas, but many wallet/hardware-wallet exports use semicolons or tabs
     * instead — without this detection, the whole header line becomes a single
     * column name and mapping can't assign any fields (the client-side PapaParse
     * used to auto-detect the delimiter).
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

    // ── Step 1 → Step 2: commit mapped rows into the staging table ──────────

    /** Builds and stages rows from the client-mapped data, then runs transfer pairing, duplicate and FX checks. */
    @Transactional
    public StageResult stageRows(List<MappedRow> mappedRows, String displayCurrency) {
        stagingRepo.deleteAllInBatch(); // defensive, in case this is called twice

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

        // Chronological order (files are usually newest-first) — same as the
        // existing mapped import (csvRows.reversed()) — needed for transfer
        // pairing below.
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

    /** Signals that a row (Trade/Einzahlung/Auszahlung) with no BTC involved
     *  should be skipped during staging instead of shown as an error. */
    private static class NoBtcTradeException extends RuntimeException {
    }

    private static final BigDecimal SATS_PER_BTC = BigDecimal.valueOf(100_000_000);

    /** True for satoshi units (case-insensitive) — as delivered by some wallet
     *  exports (Sparrow, BlueWallet, ...) instead of "BTC" as the amount unit. */
    private static boolean isSatoshiUnit(String cur) {
        if (cur == null) return false;
        String c = cur.trim();
        return c.equalsIgnoreCase("sat") || c.equalsIgnoreCase("sats")
            || c.equalsIgnoreCase("satoshi") || c.equalsIgnoreCase("satoshis");
    }

    /** True for "BTC" as well as any satoshi unit — the actual BTC-relevance
     *  check (replaces the previous plain "BTC".equalsIgnoreCase(...) checks). */
    private static boolean isBtcUnit(String cur) {
        return cur != null && (cur.trim().equalsIgnoreCase("BTC") || isSatoshiUnit(cur));
    }

    /** Converts integer satoshi amounts (e.g. 1904) to BTC (8 decimal places,
     *  exact since 1 satoshi = 0.00000001 BTC fits scale 8 cleanly). BTC values
     *  pass through unchanged so existing CoinTracking imports (already in BTC)
     *  are unaffected. */
    private static BigDecimal toBtc(BigDecimal amount, String cur) {
        if (amount == null) return null;
        return isSatoshiUnit(cur) ? amount.divide(SATS_PER_BTC, 8, RoundingMode.HALF_UP) : amount;
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
        BigDecimal exRate  = decimal(r.getExchangeRate());
        String buyCur  = blankToNull(r.getBuyCurrency());
        String sellCur = blankToNull(r.getSellCurrency());

        // Also convert the fee from satoshi to BTC where applicable (e.g. wallet
        // export network fees), normalizing the label accordingly — same as buildSelfRows().
        String feeCur = blankToNull(r.getFeeCurrency());
        BigDecimal fee = toBtc(decimal(r.getFee()), feeCur);
        row.setFees(fee);
        row.setFeesCurrency(isSatoshiUnit(feeCur) ? "BTC" : feeCur);
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
                if (isBtcUnit(buyCur)) {
                    txType = TransactionType.BUY;
                    quantity = toBtc(buyQty, buyCur);
                    quantityFiat = sellQty;
                    pricePerBtc = (quantity != null && quantity.compareTo(BigDecimal.ZERO) > 0 && sellQty != null)
                        ? sellQty.divide(quantity, 2, RoundingMode.HALF_UP) : null;
                    currency = sellCur != null ? sellCur : "EUR";
                } else if (isBtcUnit(sellCur)) {
                    txType = TransactionType.SELL;
                    quantity = toBtc(sellQty, sellCur);
                    quantityFiat = buyQty;
                    pricePerBtc = (quantity != null && quantity.compareTo(BigDecimal.ZERO) > 0 && buyQty != null)
                        ? buyQty.divide(quantity, 2, RoundingMode.HALF_UP) : null;
                    currency = buyCur != null ? buyCur : "EUR";
                } else {
                    // Neither the buy nor sell currency is BTC/satoshi -> not BTC-related,
                    // the row isn't staged but counted in stageRows().
                    throw new NoBtcTradeException();
                }
            }
            case "Einzahlung" -> {
                if (isBtcUnit(buyCur)) {
                    txType = TransactionType.TRANSFER_IN;
                    quantity = toBtc(buyQty, buyCur);
                } else {
                    // Deposit of a non-BTC currency -> not BTC-related, skip.
                    throw new NoBtcTradeException();
                }
            }
            case "Auszahlung" -> {
                if (isBtcUnit(sellCur)) {
                    txType = TransactionType.TRANSFER_OUT;
                    quantity = toBtc(sellQty, sellCur);
                } else {
                    // Withdrawal of a non-BTC currency -> not BTC-related, skip.
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

    /** "Selbst" rows (wallet export): an outflow with a network fee plus an inflow to
     *  the same position, paired via a shared transferId — mirrors
     *  {@code CsvImportService.mapSelfRows}, but error-tolerant (missing values
     *  set hasError instead of being silently dropped). */
    private List<ImportStagingRow> buildSelfRows(MappedRow r) {
        String exchange = blankToNull(r.getExchange());
        LocalDateTime dateTime = parseDate(r.getDate());

        BigDecimal amount = decimal(r.getBuyQuantity());
        String amountCur = blankToNull(r.getBuyCurrency());
        if (amount == null) {
            amount = decimal(r.getSellQuantity());
            amountCur = blankToNull(r.getSellCurrency());
        }
        amount = toBtc(amount, amountCur);

        String feeCur = blankToNull(r.getFeeCurrency());
        BigDecimal fee = decimal(r.getFee());
        fee = fee == null ? BigDecimal.ZERO : toBtc(fee, feeCur);

        BigDecimal inQuantity = null;
        if (amount != null) {
            inQuantity = amount.subtract(fee);
            if (inQuantity.compareTo(BigDecimal.ZERO) <= 0) inQuantity = amount;
        }

        // After conversion, the numeric value is always in BTC regardless of the
        // original CSV — normalize the label instead of leaving "satoshi" (which
        // would no longer match the now BTC-converted numeric value).
        String feeCurrency = isSatoshiUnit(feeCur) ? "BTC" : (feeCur != null ? feeCur : "BTC");
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

    /** Automatic transfer pairing — ports {@code CsvImportService.assignTransferIds}'s
     *  4-row lookahead window and quantity/fee tolerance to {@link ImportStagingRow}. */
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

    /** Duplicate criterion: date+type+quantity, checked against the existing DB AND
     *  within the new batch itself — rows with a transactionId are never flagged
     *  by this criterion. */
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

    /** FX criterion: currency differs from the currently selected display currency
     *  AND exchangeRate is missing/=1 — only relevant for BUY/SELL. */
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

    // ── Step 2: Review — staging CRUD ──────────────────────────────────────────

    /** Lists all staged rows in their original order for the review step. */
    public List<ImportStagingRowDTO> listStaging() {
        return stagingRepo.findAllByOrderByRowIndexAsc().stream().map(this::toDTO).toList();
    }

    /** Applies field edits to a staged row and recomputes its error/duplicate/FX-warning flags. */
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

    /** Deletes a single staged row. */
    @Transactional
    public void deleteStaging(Long id) {
        stagingRepo.deleteById(id);
    }

    /** Pairs consecutive staged rows two at a time under a fresh shared transferId. */
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

    /** Clears the transfer pairing of the given staged rows. */
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

    /** Sets the exchange rate for multiple staged rows at once and recomputes
     *  each row's FX warning (same as a single-row edit). */
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

    /** Permanently deletes multiple staged rows at once. */
    @Transactional
    public int bulkDeleteStaging(List<Long> ids) {
        List<Long> existing = ids.stream().filter(stagingRepo::existsById).toList();
        stagingRepo.deleteAllByIdInBatch(existing);
        return existing.size();
    }

    /** Sets the wallet/exchange label for multiple staged rows at once (label only —
     *  no position is created yet; that happens on final commit via
     *  {@link CsvImportService#resolvePosition}, same as a single-row edit).
     *  Recomputes the missing_exchange error for each row. */
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

    /** Marks multiple staged rows as deliberately solo transfers: each row gets
     *  its own fresh transfer ID (no shared pairing anymore) — mirrors
     *  {@code DepotRestController#bulkSoloTransfer} for already-booked
     *  transactions. Only allowed for TRANSFER_IN/TRANSFER_OUT. */
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

    /** Cancels the wizard and discards all staged rows. */
    @Transactional
    public void cancelImport() {
        stagingRepo.deleteAllInBatch();
    }

    // ── Step 2 → Step 3: final commit ────────────────────────────────────────

    /** Commits all staged rows as real transactions, recording an {@link ImportHistory} entry. */
    @Transactional
    public ConfirmResult confirmImport(String filename, Integer totalRowsHint) {
        List<ImportStagingRow> rows = stagingRepo.findAllByOrderByRowIndexAsc();

        seedCurrentPriceFromStaging(rows);

        // The history entry is created BEFORE the row loop (with placeholder counters)
        // so its ID is already known for the new transaction.import_history_id link.
        // The counter fields are filled in after the loop (second save).
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

    /** Seeds the current price for the staged rows' currency if none is set yet, using the last staged price. */
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

    // ── History ────────────────────────────────────────────────────────────

    /** Lists the most recent completed imports, newest first. */
    public List<ImportHistoryDTO> getHistory(int limit) {
        return historyRepo.findAllByOrderByImportedAtDesc(PageRequest.of(0, Math.max(1, limit))).stream()
            .map(this::toDTO)
            .toList();
    }

    /** Deletes an import history entry. With {@code deleteTransactions=false} the linked
     *  transactions are kept (only the link is cleared); with {@code true} they're deleted
     *  too. Throws if the entry doesn't exist. */
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

    // ── Mapping helpers ───────────────────────────────────────────────────────

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

    // ── DTO mapping ─────────────────────────────────────────────────────────

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

    // ── Internal transport classes ──────────────────────────────────────────

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
