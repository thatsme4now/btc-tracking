package com.thatsme4now.depot.service;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.thatsme4now.depot.controller.DepotRestController.MappedRow;
import com.thatsme4now.depot.entity.CurrentPrice;
import com.thatsme4now.depot.entity.Position;
import com.thatsme4now.depot.entity.PositionType;
import com.thatsme4now.depot.entity.Transaction;
import com.thatsme4now.depot.entity.TransactionType;
import com.thatsme4now.depot.repository.PositionRepository;
import com.thatsme4now.depot.repository.TransactionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CsvImportService {

    private final PositionRepository    positionRepo;
    private final TransactionRepository transactionRepo;
    private final DepotService     depotService;
    
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT_EN = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT_WITHOUT_SEC = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DateTimeFormatter DATE_FMT_EN_WITHOUT_SEC = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm");
    public static final DateTimeFormatter ISO_LOCAL_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    public static final DateTimeFormatter ISO_INSTANT_FMT = DateTimeFormatter.ISO_INSTANT;
    public static final DateTimeFormatter RFC_1123_FMT = DateTimeFormatter.RFC_1123_DATE_TIME;
    private static final DateTimeFormatter DATE_FMT_EN_12H = DateTimeFormatter.ofPattern("MM/dd/yyyy hh:mm:ss a", Locale.US);

    private static final Set<DateTimeFormatter> TIME_FORMATS = new HashSet<>(Arrays.asList(
            DATE_FMT,
            DATE_FMT_EN,
            DATE_FMT_WITHOUT_SEC,
            DATE_FMT_EN_WITHOUT_SEC,
            ISO_LOCAL_FMT,
            RFC_1123_FMT, 
            DATE_FMT_EN_12H 
    ));
    

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
        CsvRow last = reversed.getLast();
        
        String currency = last.currency != null ? last.currency.toUpperCase() : "EUR";
        CurrentPrice cp = depotService.getCurrentPrice(currency).orElse(new CurrentPrice());
        // set price with value from last import
        if (cp.getPrice() == null || cp.getPrice().intValue() == BigDecimal.ZERO.intValue()) {        	
        	cp.setTicker("BTC");
        	cp.setCurrency(currency);
        	cp.setPrice(last.pricePerBtc);
        	cp.setPriceDate(last.dateTime != null ? last.dateTime.toLocalDate() : java.time.LocalDate.now());
        	cp.setLoadedAt(java.time.LocalDateTime.now());
        	depotService.saveCurrentPrice(cp);
        }
        return persistRows(reversed);
    }

    private CsvRow mapMappedRow(MappedRow r) {
        if (r.getTyp() == null || r.getDate() == null || r.getExchange() == null) return null;

        LocalDateTime dateTime = null;
       
		for (DateTimeFormatter format : TIME_FORMATS) {
			try {
				dateTime = LocalDateTime.parse(r.getDate().trim(), format);
				dateTime = dateTime.withSecond(0);
				break;
			} catch (Exception e) {
				// nothing
			}
		}
        
		if(dateTime == null) {
			log.warn("Cannot parse date '{}': {}", r.getDate());
			return null;
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
            CsvRow next1 = rows.get(i + 1);
            CsvRow next2 = i < rows.size() - 2 ? rows.get(i + 2) : null;
            CsvRow next3 = i < rows.size() - 3 ? rows.get(i + 3) : null;
            CsvRow next4 = i < rows.size() - 4 ? rows.get(i + 4) : null;
            
            if (curr.type == TransactionType.TRANSFER_OUT && curr.transferId == null) {
            	if (next1.type == TransactionType.TRANSFER_IN && next1.transferId == null
                        && (curr.quantity.compareTo(next1.quantity) == 0 || curr.quantity.subtract(curr.fees).compareTo(next1.quantity) == 0)) {  
            		String uuid = UUID.randomUUID().toString();
            		curr.transferId = uuid;
            		next1.transferId = uuid;
            	} else if (next2 != null && next2.type == TransactionType.TRANSFER_IN && next2.transferId == null
                        && (curr.quantity.compareTo(next2.quantity) == 0 || curr.quantity.subtract(curr.fees).compareTo(next2.quantity) == 0)) {            		
            		String uuid = UUID.randomUUID().toString();
            		curr.transferId = uuid;
            		next2.transferId = uuid;
            	} else if (next3 != null && next3.type == TransactionType.TRANSFER_IN && next3.transferId == null
                        && (curr.quantity.compareTo(next3.quantity) == 0 || curr.quantity.subtract(curr.fees).compareTo(next3.quantity) == 0)) {            		
            		String uuid = UUID.randomUUID().toString();
            		curr.transferId = uuid;
            		next3.transferId = uuid;
            	} else if (next4 != null && next4.type == TransactionType.TRANSFER_IN && next4.transferId == null
                        && (curr.quantity.compareTo(next4.quantity) == 0 || curr.quantity.subtract(curr.fees).compareTo(next4.quantity) == 0)) {            		
            		String uuid = UUID.randomUUID().toString();
            		curr.transferId = uuid;
            		next4.transferId = uuid;
            	}
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

    public static class CsvRow {
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