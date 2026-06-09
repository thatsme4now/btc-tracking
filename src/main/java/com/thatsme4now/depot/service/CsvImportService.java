package com.thatsme4now.depot.service;

import com.thatsme4now.depot.controller.DepotRestController.MappedRow;
import com.thatsme4now.depot.entity.*;
import com.thatsme4now.depot.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CsvImportService {

    private final PositionRepository    positionRepo;
    private final TransactionRepository transactionRepo;

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    // ── Mapped Import (PapaParse frontend → JSON) ─────────────────────────────

    /**
     * Imports pre-mapped rows sent as JSON from the frontend.
     * Each row is already normalized to the internal CSV format.
     */
    public int importMapped(List<MappedRow> rows) {
        if (rows == null || rows.isEmpty()) return 0;

        // Convert MappedRow → CsvRow using the same logic as parseCsv
        List<CsvRow> csvRows = new ArrayList<>();
        for (MappedRow r : rows) {
            try {
                CsvRow row = mapMappedRow(r);
                if (row != null && row.type != null) {                	
                	csvRows.add(row);
                }
            } catch (Exception e) {
                log.warn("Skipping mapped row: {}", e.getMessage());
            }
        }
        List<CsvRow> reversed = csvRows.reversed();
        csvRows = assignTransferIds(reversed);
        return persistRows(reversed);
    }

    private CsvRow mapMappedRow(MappedRow r) {
        if (r.getTyp() == null || r.getDate() == null || r.getExchange() == null) return null;

        LocalDateTime dateTime;
        try {
            dateTime = LocalDateTime.parse(r.getDate().trim(), DATE_FMT);
            dateTime = dateTime.withSecond(0);
        } catch (Exception e) {
            // Try ISO format fallback
            try {
                dateTime = LocalDateTime.parse(r.getDate().trim(),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                dateTime = dateTime.withSecond(0);
            } catch (Exception e2) {
                log.warn("Cannot parse date '{}': {}", r.getDate(), e2.getMessage());
                return null;
            }
        }

        BigDecimal buyQty  = decimal(r.getBuyQuantity());
        BigDecimal sellQty = decimal(r.getSellQuantity());
        BigDecimal fee     = decimal(r.getFee());
        String feeCurrency = r.getFeeCurrency() != null ? r.getFeeCurrency().trim()  : null;
        BigDecimal exRate  = r.getExchangeRate() != null ? decimal(r.getExchangeRate()) : null;

        String buyCur  = r.getBuyCurrency()  != null ? r.getBuyCurrency().trim()  : null;
        String sellCur = r.getSellCurrency() != null ? r.getSellCurrency().trim() : null;
        String comment  = r.getComment()  != null ? r.getComment().trim()  : null;
        
        TransactionType txType;
        BigDecimal quantity;
        BigDecimal quantityFiat = null;
        BigDecimal pricePerBtc  = null;
        CsvRow row = new CsvRow();

        switch (r.getTyp().trim()) {
            case "Trade" -> {
                if ("BTC".equals(buyCur)) {
                    txType       = TransactionType.BUY;
                    quantity     = buyQty;
                    quantityFiat = sellQty;
                    pricePerBtc  = (buyQty != null && buyQty.compareTo(BigDecimal.ZERO) > 0 && sellQty != null)
                        ? sellQty.divide(buyQty, 2, RoundingMode.HALF_UP) : null;
                    row.currency = sellCur != null ? sellCur : "EUR";
                } else if ("BTC".equals(sellCur)) {
                    txType       = TransactionType.SELL;
                    quantity     = sellQty;
                    quantityFiat = buyQty;
                    pricePerBtc  = (sellQty != null && sellQty.compareTo(BigDecimal.ZERO) > 0 && buyQty != null)
                        ? buyQty.divide(sellQty, 2, RoundingMode.HALF_UP) : null;
                    row.currency = buyCur != null ? buyCur : "EUR";
                } else {
                    log.warn("Unknown Trade currencies: buy={} sell={}", buyCur, sellCur);
                    return null;
                }
            }
            case "Einzahlung" -> {
                if (!"BTC".equals(buyCur)) return null;
                txType   = TransactionType.TRANSFER_IN;
                quantity = buyQty;
            }
            case "Auszahlung" -> {
                if (!"BTC".equals(sellCur)) return null;
                txType   = TransactionType.TRANSFER_OUT;
                quantity = sellQty;
            }
            default -> {
                log.warn("Unknown typ: {}", r.getTyp());
                return null;
            }
        }

        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) return null;

        row.exchange     = r.getExchange().trim();
        row.dateTime     = dateTime;
        row.type         = txType;
        row.quantity     = quantity;
        row.quantityFiat = quantityFiat;
        row.pricePerBtc  = pricePerBtc;
        row.fees         = fee;
        row.feesCurrency = feeCurrency;
        row.comment		 = comment;
        row.exchangeRate = (exRate != null && exRate.compareTo(BigDecimal.ZERO) > 0)
            ? exRate : BigDecimal.ONE;
        return row;
    }

    private int persistRows(List<CsvRow> rows) {
        int inserted = 0;
        for (CsvRow row : rows) {
            Position position = resolvePosition(row.exchange);

            boolean exists = transactionRepo
                .existsByPositionIdAndDateAndTypeAndQuantity(
                    position.getId(), row.dateTime, row.type, row.quantity);

            if (exists) {
                log.debug("Skipping duplicate: {} {} {} {}", row.exchange, row.dateTime, row.type, row.quantity);
                continue;
            }

            Transaction tx = new Transaction();
            tx.setPosition(position);
            tx.setType(row.type);
            tx.setDate(row.dateTime);
            tx.setQuantity(row.quantity);
            tx.setPricePerBtc(row.pricePerBtc);
            tx.setFees(row.fees);
            tx.setFeesCurrency(row.feesCurrency);
            tx.setQuantityFiat(row.quantityFiat);
            tx.setCurrency(row.currency != null ? row.currency : "EUR");
            tx.setExchangeRate(row.exchangeRate != null ? row.exchangeRate : BigDecimal.ONE);
            tx.setTransferId(row.transferId);
            tx.setComment(row.comment);
            transactionRepo.save(tx);
            inserted++;
        }
        log.info("Import: {} rows processed, {} inserted", rows.size(), inserted);
        return inserted;
    }

    // ── CSV Parsing (legacy) ──────────────────────────────────────────────────

    private List<CsvRow> parseCsv(MultipartFile file) throws IOException {
        List<CsvRow> result = new ArrayList<>();

        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT
                 .builder()
                 .setHeader()
                 .setSkipHeaderRecord(true)
                 .setTrim(true)
                 .build()
                 .parse(reader)) {

            for (CSVRecord rec : parser) {
                try {
                    CsvRow row = mapRecord(rec);
                    if (row != null) result.add(row);
                } catch (Exception e) {
                    log.warn("Skipping CSV row {}: {}", parser.getCurrentLineNumber(), e.getMessage());
                }
            }
        }

        Collections.reverse(result);
        return result;
    }

    private CsvRow mapRecord(CSVRecord rec) {
        String typ      = get(rec, "typ");
        String kaufCur  = get(rec, "buyCurrency");
        String verkCur  = get(rec, "sellCurrency");
        String exchange = get(rec, "exchange");
        String datumStr = get(rec, "date");

        if (typ == null || datumStr == null || exchange == null) return null;

        LocalDateTime dateTime = LocalDateTime.parse(datumStr, DATE_FMT);
        dateTime = dateTime.withSecond(0);
        BigDecimal kauf    = decimal(rec, "buyQuantity");
        BigDecimal verkauf = decimal(rec, "sellQuantity");
        BigDecimal gebuehr = decimal(rec, "fee");

        TransactionType txType;
        BigDecimal quantity;
        BigDecimal quantityFiat = null;
        BigDecimal pricePerBtc  = null;
        CsvRow row = new CsvRow();

        switch (typ) {
            case "Trade" -> {
                if ("BTC".equals(kaufCur)) {
                    txType       = TransactionType.BUY;
                    quantity     = kauf;
                    quantityFiat = verkauf;
                    pricePerBtc  = (kauf != null && kauf.compareTo(BigDecimal.ZERO) > 0)
                        ? verkauf.divide(kauf, 2, RoundingMode.HALF_UP) : null;
                    row.currency = verkCur != null ? verkCur : "EUR";
                } else if ("BTC".equals(verkCur)) {
                    txType       = TransactionType.SELL;
                    quantity     = verkauf;
                    quantityFiat = kauf;
                    pricePerBtc  = (verkauf != null && verkauf.compareTo(BigDecimal.ZERO) > 0)
                        ? kauf.divide(verkauf, 2, RoundingMode.HALF_UP) : null;
                    row.currency = kaufCur != null ? kaufCur : "EUR";
                } else {
                    log.warn("Unknown Trade currencies: Kauf={} Verkauf={}", kaufCur, verkCur);
                    return null;
                }
            }
            case "Einzahlung" -> {
                if (!"BTC".equals(kaufCur)) return null;
                txType   = TransactionType.TRANSFER_IN;
                quantity = kauf;
            }
            case "Auszahlung" -> {
                if (!"BTC".equals(verkCur)) return null;
                txType   = TransactionType.TRANSFER_OUT;
                quantity = verkauf;
            }
            default -> {
                log.warn("Unknown Typ: {}", typ);
                return null;
            }
        }

        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) return null;

        row.exchange    = exchange;
        row.dateTime    = dateTime;
        row.type        = txType;
        row.quantity    = quantity;
        row.quantityFiat = quantityFiat;
        row.pricePerBtc = pricePerBtc;
        row.fees        = gebuehr;
        return row;
    }

    // ── Transfer pairing ──────────────────────────────────────────────────────

    private List<CsvRow> assignTransferIds(List<CsvRow> rows) {
        for (int i = 0; i < rows.size() - 1; i++) {
            CsvRow curr = rows.get(i);
            CsvRow next = rows.get(i + 1);

            if (curr.type == TransactionType.TRANSFER_OUT
                    && next.type == TransactionType.TRANSFER_IN
                    && curr.quantity.compareTo(next.quantity) == 0) {

                String uuid = UUID.randomUUID().toString();
                curr.transferId = uuid;
                next.transferId = uuid;
                i++;
            }
        }
        return rows;
    }

    // ── Position resolution ───────────────────────────────────────────────────

    public Position resolvePosition(String label) {
        return positionRepo.findByLabel(label).orElseGet(() -> {
            Position p = new Position();
            p.setLabel(label);
            p.setType(label.toLowerCase().contains("wallet")
                ? PositionType.WALLET
                : PositionType.EXCHANGE);
            log.info("Auto-created position: {} ({})", label, p.getType());
            return positionRepo.save(p);
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String get(CSVRecord rec, String col) {
        try {
            String v = rec.get(col);
            return (v == null || v.isBlank()) ? null : v.trim();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private BigDecimal decimal(CSVRecord rec, String col) {
        return decimal(get(rec, col));
    }

    private BigDecimal decimal(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return new BigDecimal(v.trim().replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── Internal DTO ──────────────────────────────────────────────────────────

    private static class CsvRow {
        String          exchange;
        LocalDateTime   dateTime;
        TransactionType type;
        BigDecimal      quantity;
        BigDecimal      quantityFiat;
        BigDecimal      pricePerBtc;
        BigDecimal      fees;
        String          feesCurrency;
        BigDecimal      exchangeRate;
        String          transferId;
        String          currency;
        String          comment;
    }
}