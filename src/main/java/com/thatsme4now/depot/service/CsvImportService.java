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

/**
 * Parses and persists CSV/mapped-row transaction imports: date-format
 * detection, buy/sell/transfer classification, automatic transfer pairing,
 * and duplicate detection.
 */
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
    // Wallet exports (Sparrow, BlueWallet, ...) often use ISO-8601 WITH a timezone
    // offset, e.g. "2022-01-13T15:56:20-03:00" — ISO_LOCAL_FMT alone fails on the
    // unparsed "-03:00" remainder. Parsing with this formatter simply keeps the
    // local date/time part in the string and discards the offset, consistent with
    // every other format here being handled without any timezone conversion.
    public static final DateTimeFormatter ISO_OFFSET_FMT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    public static final DateTimeFormatter RFC_1123_FMT = DateTimeFormatter.RFC_1123_DATE_TIME;
    private static final DateTimeFormatter DATE_FMT_EN_12H = DateTimeFormatter.ofPattern("MM/dd/yyyy hh:mm:ss a", Locale.US);
    public static final DateTimeFormatter ISO_CUSTOM_FORMAT = DateTimeFormatter.ofPattern("yyy-MM-dd HH:mm:ss");
    
    private static final DateTimeFormatter DATE_FMT_FLEX = DateTimeFormatter.ofPattern("d.M.yyyy HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT_FLEX_WITHOUT_SEC = DateTimeFormatter.ofPattern("d.M.yyyy HH:mm");


    private static final Set<DateTimeFormatter> TIME_FORMATS = new HashSet<>(Arrays.asList(
            DATE_FMT,
            DATE_FMT_FLEX,
            DATE_FMT_EN,
            DATE_FMT_WITHOUT_SEC,
            DATE_FMT_FLEX_WITHOUT_SEC,
            DATE_FMT_EN_WITHOUT_SEC,
            ISO_LOCAL_FMT,
            ISO_OFFSET_FMT,
            RFC_1123_FMT,
            DATE_FMT_EN_12H,
            ISO_CUSTOM_FORMAT
    ));
    

    // ── Mapped Import (PapaParse frontend → JSON) ─────────────────────────────

    /**
     * Imports pre-mapped rows sent as JSON from the frontend.
     * Each row is already normalized to the internal CSV format.
     */
    public ImportResult importMapped(List<MappedRow> rows) {
        if (rows == null || rows.isEmpty()) return null;

        // Convert MappedRow -> CsvRow using the same logic as parseCsv
        List<CsvRow> csvRows = new ArrayList<>();
        for (MappedRow r : rows) {
            try {
            	if ("Selbst".equals(r.getTyp())) {
                    csvRows.addAll(mapSelfRows(r));
                } else {
                    CsvRow row = mapMappedRow(r);
                    if (row != null && row.type != null) {
                        csvRows.add(row);
                    }
                }
            } catch (Exception e) {
                log.warn("Skipping mapped row: {}", e.getMessage());
            }
        }
        
        CsvRow lastPrice = csvRows.stream().filter(row -> row.pricePerBtc != null).findFirst().orElse(null);
        List<CsvRow> reversed = csvRows.reversed();
        try {        	
        	assignTransferIds(reversed);
        } catch (Exception e) {
			// nothing to do. just automatic transfer id assigning failed
		}

        String currency = lastPrice != null && lastPrice.currency != null ? lastPrice.currency.toUpperCase() : "EUR";
        CurrentPrice cp = depotService.getCurrentPrice(currency).orElse(new CurrentPrice());
        // set price with value from last import
        if (cp.getPrice() == null || cp.getPrice().intValue() == BigDecimal.ZERO.intValue()) {        	
        	cp.setTicker("BTC");
        	cp.setCurrency(currency);
        	if(lastPrice != null) {        		
        		cp.setPrice(lastPrice.pricePerBtc);
        	} else {
        		cp.setPrice(new BigDecimal(50000));
        	}
        	cp.setPriceDate(lastPrice != null && lastPrice.dateTime != null ? lastPrice.dateTime.toLocalDate() : java.time.LocalDate.now());
        	cp.setLoadedAt(java.time.LocalDateTime.now());
        	depotService.saveCurrentPrice(cp);
        }
        return persistRows(reversed);
    }
    
    /**
     * A "Selbst" (self) row from a wallet export represents a self-transfer: an outflow
     * with a network fee and a simultaneous inflow to the SAME position, minus the fee.
     * Produces two paired transactions (TRANSFER_OUT + TRANSFER_IN, same transferId).
     */
    private List<CsvRow> mapSelfRows(MappedRow r) {
        if (r.getDate() == null || r.getExchange() == null) return Collections.emptyList();

        LocalDateTime dateTime = getLocalDateTimeByString(r.getDate().trim());
        if (dateTime == null) {
            log.warn("Cannot parse date for Selbst row: {}", r.getDate());
            return Collections.emptyList();
        }

        // Amount can be in buyQty OR sellQty depending on mapping (wallet exports have only one "Amount" column)
        BigDecimal amount = decimal(r.getBuyQuantity());
        if (amount == null) amount = decimal(r.getSellQuantity());
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) return Collections.emptyList();

        BigDecimal fee = decimal(r.getFee());
        if (fee == null) fee = BigDecimal.ZERO;

        BigDecimal inQuantity = amount.subtract(fee);
        if (inQuantity.compareTo(BigDecimal.ZERO) <= 0) inQuantity = amount; // fallback if fee >= amount

        String feeCurrency = r.getFeeCurrency() != null && !r.getFeeCurrency().isBlank()
                ? r.getFeeCurrency().trim() : "BTC";
        String comment  = r.getComment() != null ? r.getComment().trim() : null;
        String exchange = r.getExchange().trim();
        String transferId = UUID.randomUUID().toString();

        String baseTxId = r.getTransactionId() != null && !r.getTransactionId().isBlank()
                ? r.getTransactionId().trim() : null;

        CsvRow out = new CsvRow();
        out.exchange      = exchange;
        out.dateTime       = dateTime;
        out.type           = TransactionType.TRANSFER_OUT;
        out.quantity       = amount;
        out.fees           = fee;
        out.feesCurrency   = feeCurrency;
        out.comment        = comment;
        out.transferId     = transferId;
        out.exchangeRate   = BigDecimal.ONE;
        out.transactionId  = baseTxId != null ? baseTxId + "-out" : null;

        CsvRow in = new CsvRow();
        in.exchange       = exchange;
        in.dateTime        = dateTime;
        in.type            = TransactionType.TRANSFER_IN;
        in.quantity        = inQuantity;
        in.comment         = comment;
        in.transferId      = transferId;
        in.exchangeRate    = BigDecimal.ONE;
        in.transactionId   = baseTxId != null ? baseTxId + "-in" : null;

        return new ArrayList<>(List.of(in, out));
    }

    /** Maps a single mapped CSV row (Trade/Einzahlung/Auszahlung) to an internal {@link CsvRow}. */
    private CsvRow mapMappedRow(MappedRow r) {
        if (r.getTyp() == null || r.getDate() == null || r.getExchange() == null) return null;

        LocalDateTime dateTime = getLocalDateTimeByString(r.getDate().trim());
        
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
        String transactionId  = r.getTransactionId()  != null ? r.getTransactionId().trim()  : null;
        String transferId     = r.getTransferId()     != null && !r.getTransferId().trim().isBlank() ? r.getTransferId().trim() : null;

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
        row.transactionId = transactionId;
        row.transferId   = transferId;
        row.exchangeRate = (exRate != null && exRate.compareTo(BigDecimal.ZERO) > 0)
            ? exRate : BigDecimal.ONE;
        return row;
    }

	/** Tries every known date format until one parses; returns null if none match. */
	public LocalDateTime getLocalDateTimeByString(String date) {
		LocalDateTime dateTime = null;
       
		for (DateTimeFormatter format : TIME_FORMATS) {
			try {
				dateTime = LocalDateTime.parse(date, format);
				break;
			} catch (Exception e) {
				// nothing
				System.out.println();
			}
		}
		return dateTime;
	}

	/** Saves each row as a transaction, skipping rows whose transactionId already exists. */
	private ImportResult persistRows(List<CsvRow> rows) {
	    ImportResult result = new ImportResult();
	    for (CsvRow row : rows) {
	        Position position = resolvePosition(row.exchange);

	        boolean isDuplicate = row.transactionId == null && transactionRepo
	            .existsByDateAndTypeAndQuantity(row.dateTime, row.type, row.quantity);

	        // row with transactionId already exists
	        if(row.transactionId != null && transactionRepo.existsByTransactionId(row.transactionId)) {
	        	result.ignoredByTransactionId++;
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
	        tx.setTransactionId(row.transactionId);
	        tx.setDuplicate(isDuplicate);

	        if (tx.getTransactionId() == null) {
	            tx.setTransactionId(UUID.randomUUID().toString());
	        }
	        transactionRepo.save(tx);
	        result.inserted++;
	        result.lastImportIds.add(tx.getId());
	        if (isDuplicate) {
	            result.duplicateIds.add(tx.getId());
	        }
	    }
	    return result;
	}

    // ── CSV Parsing (legacy) ──────────────────────────────────────────────────

    /** Legacy path: parses a raw CSV file directly (superseded by the mapped-row import above). */
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

    /** Auto-pairs unmatched TRANSFER_OUT rows with a same/near-quantity TRANSFER_IN within the next 4 rows. */
    private void assignTransferIds(List<CsvRow> rows) {
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
    }

    // ── Position resolution ───────────────────────────────────────────────────

    /** Finds a position by label, auto-creating one (guessing WALLET vs. EXCHANGE from the name) if absent. */
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
        String          transactionId;
    }
    
    public static class ImportResult {
        public int inserted;
        public int ignoredByTransactionId;
        public List<Long> duplicateIds = new ArrayList<>();
        public List<Long> lastImportIds = new ArrayList<>();

    }
}