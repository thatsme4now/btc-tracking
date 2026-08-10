package com.thatsme4now.depot.controller;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.thatsme4now.depot.dto.PortfolioMetricsDTO;
import com.thatsme4now.depot.dto.TransactionDTO;
import com.thatsme4now.depot.entity.AppSettings;
import com.thatsme4now.depot.entity.CurrentPrice;
import com.thatsme4now.depot.entity.Position;
import com.thatsme4now.depot.entity.PriceHistory;
import com.thatsme4now.depot.entity.Transaction;
import com.thatsme4now.depot.entity.TransactionType;
import com.thatsme4now.depot.service.CsvEncryptionService;
import com.thatsme4now.depot.service.CsvImportService;
import com.thatsme4now.depot.service.CsvImportService.ImportResult;
import com.thatsme4now.depot.service.DataExportService;
import com.thatsme4now.depot.service.DepotService;
import com.thatsme4now.depot.service.FlowService;
import com.thatsme4now.depot.service.HistoricalPriceService;
import com.thatsme4now.depot.service.HoldingsYearlyService;
import com.thatsme4now.depot.service.MonthlyOverviewService;
import com.thatsme4now.depot.service.MonthlyPriceService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Main REST API for the depot app: transaction/position CRUD, current and
 * historical BTC prices, portfolio metrics, the flow/yearly/holdings
 * visualization data, CSV/JSON import-export, and app settings.
 */
@RestController
@RequestMapping("/api/btc-tracking")
@RequiredArgsConstructor
public class DepotRestController {

    private final DepotService     depotService;
    private final CsvImportService csvImportService;
    private final CsvEncryptionService csvEncryptionService;
    private final DataExportService dataExportService;
    private final FlowService flowService;
    private final HoldingsYearlyService holdingsYearlyService;
    private final HistoricalPriceService historicalPriceService;
    private final MonthlyPriceService monthlyPriceService;
    private final MonthlyOverviewService monthlyOverviewService;

    /** Returns the yearly holdings breakdown (per-year balance, buys/sells, P/L) for the holdings page. */
    @GetMapping("/holdings/yearly")
    public List<com.thatsme4now.depot.dto.YearlyHoldingsDTO> getYearlyHoldings(
            @RequestParam(required = false, name = "currency") String currency,
            HttpServletRequest request) {
        String cur = (currency != null && !currency.isBlank())
                ? currency
                : depotService.readCookie(request, "depot-currency", "EUR");
        return holdingsYearlyService.getYearlyHoldings(cur);
    }

    /** Returns the year-end historical BTC prices used for unrealized P/L calculations. */
    @GetMapping("/historical-prices")
    public List<com.thatsme4now.depot.dto.HistoricalPriceDTO> getHistoricalPrices(
            @RequestParam(required = false, name = "currency") String currency,
            HttpServletRequest request) {
        String cur = (currency != null && !currency.isBlank())
                ? currency
                : depotService.readCookie(request, "depot-currency", "EUR");
        return historicalPriceService.getYearly(cur);
    }

    /** Sets a manual year-end reference price for a given year/currency. */
    @PutMapping("/historical-prices")
    public ResponseEntity<Map<String, Object>> upsertHistoricalPrice(@RequestBody HistoricalPriceUpdateRequest req) {
        try {
            com.thatsme4now.depot.dto.HistoricalPriceDTO dto =
                    historicalPriceService.upsert(req.getYear(), req.getCurrency(), req.getPrice());
            return ResponseEntity.ok(Map.of(
                    "year", dto.getYear(),
                    "currency", dto.getCurrency(),
                    "price", dto.getPrice()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Returns the manual monthly reference prices used for unrealized P/L calculations. */
    @GetMapping("/monthly-prices")
    public List<com.thatsme4now.depot.dto.MonthlyPriceDTO> getMonthlyPrices(
            @RequestParam(required = false, name = "currency") String currency,
            HttpServletRequest request) {
        String cur = (currency != null && !currency.isBlank())
                ? currency
                : depotService.readCookie(request, "depot-currency", "EUR");
        return monthlyPriceService.getMonthly(cur);
    }

    /**
     * Returns the full monthly price history (every month in the
     * {@code monthly_price} table plus the current live price) for the
     * yearly overview's Bitcoin price chart, see {@link MonthlyPriceService#getPriceHistory}.
     */
    @GetMapping("/monthly-prices/history")
    public List<com.thatsme4now.depot.dto.MonthlyPriceDTO> getMonthlyPriceHistory(
            @RequestParam(required = false, name = "currency") String currency,
            HttpServletRequest request) {
        String cur = (currency != null && !currency.isBlank())
                ? currency
                : depotService.readCookie(request, "depot-currency", "EUR");
        return monthlyPriceService.getPriceHistory(cur);
    }

    /** Sets a manual monthly reference price. */
    @PutMapping("/monthly-prices")
    public ResponseEntity<Map<String, Object>> upsertMonthlyPrice(@RequestBody MonthlyPriceUpdateRequest req) {
        try {
            com.thatsme4now.depot.dto.MonthlyPriceDTO dto =
                    monthlyPriceService.upsert(req.getYear(), req.getMonth(), req.getCurrency(), req.getPrice());
            return ResponseEntity.ok(Map.of(
                    "year", dto.getYear(),
                    "month", dto.getMonth(),
                    "currency", dto.getCurrency(),
                    "price", dto.getPrice()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Returns balance/value time series for the yearly view, either the full overview or a single year. */
    @GetMapping("/yearly-overview")
    public com.thatsme4now.depot.dto.YearlyOverviewDTO getYearlyOverview(
            @RequestParam(required = false, name = "year") Integer year,
            @RequestParam(required = false, name = "currency") String currency,
            HttpServletRequest request) {
        String cur = (currency != null && !currency.isBlank())
                ? currency
                : depotService.readCookie(request, "depot-currency", "EUR");
        return monthlyOverviewService.getOverview(year, cur);
    }

    /** Returns the Sankey flow graph (nodes/links) for the flow diagram page, optionally filtered by date range or position. */
    @GetMapping("/flow")
    public com.thatsme4now.depot.dto.FlowGraphDTO getFlow(
            @RequestParam(required = false, name = "from") String from,
            @RequestParam(required = false, name = "to") String to,
            @RequestParam(required = false, name = "positionId") Long positionId) {
		java.time.LocalDate fromDate = (from != null && !from.isBlank()) ? java.time.LocalDate.parse(from) : null;
		java.time.LocalDate toDate = (to != null && !to.isBlank()) ? java.time.LocalDate.parse(to) : null;
		return flowService.buildFlowGraph(fromDate, toDate, positionId);
    }

    /** Returns the raw daily BTC price history. */
    @GetMapping("/history")
    public List<PriceHistory> getHistory() {
        return depotService.getHistory();
    }

    // ── Mapped import from the legacy PapaParse frontend flow ──────────────────

    /** Imports transactions from rows already mapped client-side (legacy CSV import path). */
    @PostMapping("/import-mapped")
    public ResponseEntity<Map<String, Object>> importMapped(
            @RequestBody MappedImportRequest req) {
        try {
            ImportResult result = csvImportService.importMapped(req.getRows());
            return ResponseEntity.ok(Map.of(
            	    "inserted", result.inserted,
            	    "duplicateIds", result.duplicateIds,
            	    "lastImportIds", result.lastImportIds,
            	    "ignoredByTransactionId", result.ignoredByTransactionId
            	));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * POST /api/depot/import-enc
     * Multipart: file=transactions_export.enc, password=secret
     *
     * Decrypts the file, parses CSV rows using the known fixed format
     * (no column mapping needed — format is defined by our own export).
     */
    @PostMapping(value = "/import-enc", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> importEnc(
            @org.springframework.web.bind.annotation.RequestParam("file")     org.springframework.web.multipart.MultipartFile file,
            @org.springframework.web.bind.annotation.RequestParam("password") String password) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "No file uploaded."));
            }
            if (password == null || password.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Password is required."));
            }
 
            byte[] encBytes = file.getBytes();
            byte[] csvBytes;
            try {
                csvBytes = csvEncryptionService.decrypt(encBytes, password);
            } catch (CsvEncryptionService.EncryptionException e) {
                return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
            }
 
            // Strip UTF-8 BOM if present
            if (csvBytes.length >= 3
                    && (csvBytes[0] & 0xFF) == 0xEF
                    && (csvBytes[1] & 0xFF) == 0xBB
                    && (csvBytes[2] & 0xFF) == 0xBF) {
                csvBytes = java.util.Arrays.copyOfRange(csvBytes, 3, csvBytes.length);
            }
 
            // Parse CSV — fixed format matches our export header
            String csvContent = new String(csvBytes, java.nio.charset.StandardCharsets.UTF_8);
            java.util.List<MappedRow> rows = new java.util.ArrayList<>();
 
            try (org.apache.commons.csv.CSVParser parser = org.apache.commons.csv.CSVFormat.DEFAULT.builder()
                    .setHeader("typ", "date", "exchange",
                               "buyQuantity", "buyCurrency",
                               "sellQuantity", "sellCurrency",
                               "fee", "feeCurrency", "exchangeRate", "comment", "transactionId", "transferId")
                    .setSkipHeaderRecord(true)
                    .setTrim(true)
                    .build()
                    .parse(new java.io.StringReader(csvContent))) {
 
                for (org.apache.commons.csv.CSVRecord rec : parser) {
                    MappedRow r = new MappedRow();
                    r.setTyp(rec.get("typ"));
                    r.setDate(rec.get("date"));
                    r.setExchange(rec.get("exchange"));
                    r.setBuyQuantity(rec.get("buyQuantity"));
                    r.setBuyCurrency(rec.get("buyCurrency"));
                    r.setSellQuantity(rec.get("sellQuantity"));
                    r.setSellCurrency(rec.get("sellCurrency"));
                    r.setFee(rec.get("fee"));
                    r.setFeeCurrency(rec.get("feeCurrency"));
                    r.setExchangeRate(rec.get("exchangeRate"));
                    r.setComment(rec.get("comment"));
                    try {                    	
                    	r.setTransactionId(rec.get("transactionId"));
                    } catch (Exception e) {
						// does not exists
                    	r.setTransactionId(UUID.randomUUID().toString());
					}
                    try {
                        r.setTransferId(rec.get("transferId"));
                    } catch (Exception e) {
                        // older .enc export without transferId column
                        r.setTransferId(null);
                    }
                    rows.add(r);
                }
            }
 
            ImportResult result = csvImportService.importMapped(rows);
            return ResponseEntity.ok(Map.of(
            	    "inserted", result.inserted,
            	    "duplicateIds", result.duplicateIds,
            	    "lastImportIds", result.lastImportIds,
            	    "ignoredByTransactionId", result.ignoredByTransactionId
            	));
 
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Transactions CRUD ─────────────────────────────────────────────────────

    /** Returns all transactions. */
    @GetMapping("/transactions")
    public List<TransactionDTO> getAllTransactions() {
        return depotService.getAllTransactions();
    }
    
    /**
     * Creates a new transaction. For a TRANSFER_OUT with a transfer target,
     * also creates the paired TRANSFER_IN transaction with a shared transfer id.
     */
    @PostMapping("/transactions")
    public ResponseEntity<TransactionDTO> addTransaction(
            @RequestBody TransactionUpdateRequest req) {
        Transaction tx = new Transaction();
        try {        	
        	mapTransactionRequestToTransaction(req, tx);
        } catch (Exception e) {
        	return ResponseEntity.badRequest().body(null);
		}
        Position position = csvImportService.resolvePosition(req.getExchange());
        tx.setPosition(position);
        tx.setTransactionId(UUID.randomUUID().toString());
        // pairing: assign a shared transfer id when TRANSFER_OUT has a transferTarget
        if (tx.getType() == TransactionType.TRANSFER_OUT
                && req.getTransferTarget() != null
                && !req.getTransferTarget().isBlank()) {

            String uuid = UUID.randomUUID().toString();
            tx.setTransferId(uuid);
            Transaction txIn = new Transaction();
            txIn.setType(TransactionType.TRANSFER_IN);
            LocalDateTime transferInDateTime = req.getTransferInDate() != null
                    ? csvImportService.getLocalDateTimeByString(req.getTransferInDate())
                    : null;
            txIn.setDate(transferInDateTime != null ? transferInDateTime : tx.getDate());
            txIn.setQuantity(req.getTransferInQuantity() != null ? req.getTransferInQuantity() : tx.getQuantity());
            txIn.setExchangeRate(BigDecimal.ONE);
            txIn.setCurrency(tx.getCurrency());
            txIn.setTransferId(uuid);
            txIn.setComment(tx.getComment());
            Position targetPos = csvImportService.resolvePosition(req.getTransferTarget());
            txIn.setPosition(targetPos);
            depotService.saveTransaction(txIn);
        }

        Long id = depotService.saveTransaction(tx).getId();
        return ResponseEntity.ok(depotService.getAllTransactions()
                .stream().filter(d -> d.getId().equals(id)).findFirst().orElseThrow());
    }

    /** Updates an existing transaction's fields. */
    @PutMapping("/transactions/{id}")
    public ResponseEntity<TransactionDTO> updateTransaction(
            @PathVariable("id") Long id,
            @RequestBody TransactionUpdateRequest req) {

        return depotService.getTransaction(id).map(tx -> {
            mapTransactionRequestToTransaction(req, tx);
            depotService.saveTransaction(tx);
            return ResponseEntity.ok(depotService.getAllTransactions()
                .stream().filter(d -> d.getId().equals(id)).findFirst().orElseThrow());
        }).orElse(ResponseEntity.notFound().build());
    }

	/** Applies non-null fields from the request onto the transaction entity. */
	private void mapTransactionRequestToTransaction(TransactionUpdateRequest req, Transaction tx) {
		LocalDateTime dateTime = csvImportService.getLocalDateTimeByString(req.getDate());
		
		if (req.getDate()         != null) tx.setDate(dateTime);
		if (req.getType()         != null) tx.setType(req.getType());
		if (req.getQuantity() != null) tx.setQuantity(req.getQuantity());
		if (req.getQuantityFiat() != null && req.getQuantity() != null) {
		    java.math.BigDecimal rate = req.getExchangeRate() != null ? req.getExchangeRate() : java.math.BigDecimal.ONE;
		    tx.setPricePerBtc(req.getQuantityFiat()
		        .divide(req.getQuantity(), 2, java.math.RoundingMode.HALF_UP));
		    tx.setQuantityFiat(req.getQuantityFiat());
		}
		if (req.getCurrency()     != null) tx.setCurrency(req.getCurrency());
		if (req.getExchangeRate() != null) tx.setExchangeRate(req.getExchangeRate());
		if (req.getExchange()     != null) {
			Position position = csvImportService.resolvePosition(req.getExchange());
	    	tx.setPosition(position);
		}
		tx.setFees(req.getFees());
		tx.setFeesCurrency(req.getFeesCurrency());
		tx.setComment(req.getComment());
	}
    
	/**
	 * "Reset app": clears the same tables as the app-lock reset (see
	 * {@link com.thatsme4now.depot.service.AppLockService#reset}), leaving
	 * the app equivalent to a fresh install with no data.
	 */
	@DeleteMapping("/")
    public ResponseEntity<Void> deleteAllTransaction() {
        dataExportService.clearAll();
        return ResponseEntity.noContent().build();
    }
	
	/** Deletes a single transaction. */
	@DeleteMapping("/transactions/{id}")
    public ResponseEntity<Void> deleteTransaction(@PathVariable("id") Long id) {
        depotService.deleteTransaction(id);
        return ResponseEntity.noContent().build();
    }
	
	/** Clears the duplicate flag on the selected transactions. */
	@PostMapping("/transactions/bulk-clear-duplicate")
    public ResponseEntity<Map<String, Object>> bulkClearDuplicate(@RequestBody BulkClearDuplicateRequest req) {
        if (req.getIds() == null || req.getIds().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing ids"));
        }

        int cleared = 0;
        for (Long id : req.getIds()) {
            Transaction tx = depotService.getTransaction(id).orElse(null);
            if (tx == null || !tx.isDuplicate()) continue;
            tx.setDuplicate(false);
            depotService.saveTransaction(tx);
            cleared++;
        }
        return ResponseEntity.ok(Map.of("cleared", cleared));
    }


	/**
	 * Exports transactions as CSV, either the internal re-importable format
	 * or a CoinTracking-compatible format. If a password is given, the CSV
	 * is AES-256-GCM encrypted before being returned.
	 */
	@PostMapping("/export")
	public void exportCsv(
	        @RequestBody(required = false) ExportRequest req,
	        HttpServletResponse response) throws java.io.IOException {

	    boolean coinTracking = req != null && req.isCoinTracking();
	    String password = (!coinTracking && req != null && req.getPassword() != null
	                       && !req.getPassword().isBlank())
	                      ? req.getPassword() : null;

	    List<TransactionDTO> transactions = depotService.getAllTransactions();
	    if (req != null && req.getIds() != null && !req.getIds().isEmpty()) {
	        java.util.Set<Long> idSet = new java.util.HashSet<>(req.getIds());
	        transactions = transactions.stream()
	                .filter(t -> idSet.contains(t.getId()))
	                .collect(Collectors.toList());
	    }

	    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
	    baos.write(0xEF); baos.write(0xBB); baos.write(0xBF);

	    if (coinTracking) {
	        writeCoinTrackingCsv(transactions, baos);
	    } else {
	        writeInternalCsv(transactions, baos);
	    }

	    byte[] csvBytes = baos.toByteArray();

	    if (password != null) {
	        byte[] encBytes = csvEncryptionService.encrypt(csvBytes, password);
	        response.setContentType("application/octet-stream");
	        response.setHeader("Content-Disposition", "attachment; filename=transactions_export.enc");
	        response.getOutputStream().write(encBytes);
	    } else {
	        response.setContentType("text/csv; charset=UTF-8");
	        String filename = coinTracking ? "cointracking_export.csv" : "transactions_export.csv";
	        response.setHeader("Content-Disposition", "attachment; filename=" + filename);
	        response.getOutputStream().write(csvBytes);
	    }
	}

	/** Writes the internal, re-importable CSV format. */
	private void writeInternalCsv(List<TransactionDTO> transactions, java.io.ByteArrayOutputStream baos) throws java.io.IOException {
	    try (org.apache.commons.csv.CSVPrinter printer = new org.apache.commons.csv.CSVPrinter(
	            new java.io.OutputStreamWriter(baos, java.nio.charset.StandardCharsets.UTF_8),
	            org.apache.commons.csv.CSVFormat.DEFAULT.builder()
	                .setHeader("typ", "date", "exchange",
	                           "buyQty", "buyCur",
	                           "sellQty", "sellCur",
	                           "fee", "feeCur", "exchangeRate", "comment",
	                           "transactionId", "transferId")
	                .setDelimiter(",")
	                .setQuote('"')
	                .setQuoteMode(org.apache.commons.csv.QuoteMode.ALL)
	                .build())) {

	        for (TransactionDTO tx : transactions) {
	            String typ, kauf = "", kaufCur = "", verkauf = "", verkCur = "";

	            switch (tx.getType()) {
	                case BUY -> {
	                    typ     = "Trade";
	                    kauf    = tx.getQuantity().toPlainString();
	                    kaufCur = "BTC";
	                    java.math.BigDecimal rate = tx.getExchangeRate() != null
	                        ? tx.getExchangeRate() : java.math.BigDecimal.ONE;
	                    java.math.BigDecimal fiatAmt = tx.getQuantityFiat() != null
	                        ? tx.getQuantityFiat().divide(rate, 2, java.math.RoundingMode.HALF_UP)
	                        : java.math.BigDecimal.ZERO;
	                    verkauf = fiatAmt.toPlainString();
	                    verkCur = tx.getCurrency() != null ? tx.getCurrency() : "EUR";
	                }
	                case SELL -> {
	                    typ      = "Trade";
	                    java.math.BigDecimal rate2 = tx.getExchangeRate() != null
	                        ? tx.getExchangeRate() : java.math.BigDecimal.ONE;
	                    java.math.BigDecimal fiatAmt2 = tx.getQuantityFiat() != null
	                        ? tx.getQuantityFiat().divide(rate2, 2, java.math.RoundingMode.HALF_UP)
	                        : java.math.BigDecimal.ZERO;
	                    kauf    = fiatAmt2.toPlainString();
	                    kaufCur = tx.getCurrency() != null ? tx.getCurrency() : "EUR";
	                    verkauf = tx.getQuantity().toPlainString();
	                    verkCur = "BTC";
	                }
	                case TRANSFER_IN -> {
	                    typ     = "Einzahlung";
	                    kauf    = tx.getQuantity().toPlainString();
	                    kaufCur = "BTC";
	                }
	                case TRANSFER_OUT -> {
	                    typ     = "Auszahlung";
	                    verkauf = tx.getQuantity().toPlainString();
	                    verkCur = "BTC";
	                }
	                default -> typ = "";
	            }

	            String datum = tx.getDate() != null
	                ? tx.getDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))
	                : "";
	            String fee          = tx.getFees()         != null ? tx.getFees().toPlainString()         : "";
	            String feeCurrency  = tx.getFeesCurrency() != null ? tx.getFeesCurrency()                 : "";
	            String exchangeRate = tx.getExchangeRate() != null ? tx.getExchangeRate().toPlainString() : "";
	            String transactionId  = tx.getTransactionId() != null ? tx.getTransactionId()             : "";
	            String transferId     = tx.getTransferId()    != null ? tx.getTransferId()                : "";

	            printer.printRecord(typ, datum, tx.getPositionLabel(),
	                                kauf, kaufCur, verkauf, verkCur,
	                                fee, feeCurrency, exchangeRate, tx.getComment(), transactionId, transferId);
	        }
	    }
	}

	/** Writes a CoinTracking-compatible CSV (standard import template, German column headers; duplicate "Cur." headers are expected by CoinTracking). */
	private void writeCoinTrackingCsv(List<TransactionDTO> transactions, java.io.ByteArrayOutputStream baos) throws java.io.IOException {
	    try (org.apache.commons.csv.CSVPrinter printer = new org.apache.commons.csv.CSVPrinter(
	            new java.io.OutputStreamWriter(baos, java.nio.charset.StandardCharsets.UTF_8),
	            org.apache.commons.csv.CSVFormat.DEFAULT.builder()
	                .setHeader("Typ", "Kauf", "Cur.", "Verkauf", "Cur.", "Gebühr", "Cur.",
	                           "Börse", "Gruppe", "Kommentar", "Datum","From Address","To Address","Tx Hash","Sell From Address","Sell To Address")
	                .setDelimiter(",")
	                .setQuote('"')
	                .setQuoteMode(org.apache.commons.csv.QuoteMode.ALL)
	                .build())) {

	        for (TransactionDTO tx : transactions) {
	            String typ, kauf = "", kaufCur = "", verkauf = "", verkCur = "";

	            switch (tx.getType()) {
	                case BUY -> {
	                    typ     = "Trade";
	                    kauf    = tx.getQuantity().toPlainString();
	                    kaufCur = "BTC";
	                    java.math.BigDecimal rate = tx.getExchangeRate() != null
	                        ? tx.getExchangeRate() : java.math.BigDecimal.ONE;
	                    java.math.BigDecimal fiatAmt = tx.getQuantityFiat() != null
	                        ? tx.getQuantityFiat().divide(rate, 8, java.math.RoundingMode.HALF_UP)
	                        : java.math.BigDecimal.ZERO;
	                    verkauf = fiatAmt.toPlainString();
	                    verkCur = tx.getCurrency() != null ? tx.getCurrency() : "EUR";
	                }
	                case SELL -> {
	                    typ      = "Trade";
	                    java.math.BigDecimal rate2 = tx.getExchangeRate() != null
	                        ? tx.getExchangeRate() : java.math.BigDecimal.ONE;
	                    java.math.BigDecimal fiatAmt2 = tx.getQuantityFiat() != null
	                        ? tx.getQuantityFiat().divide(rate2, 8, java.math.RoundingMode.HALF_UP)
	                        : java.math.BigDecimal.ZERO;
	                    kauf    = fiatAmt2.toPlainString();
	                    kaufCur = tx.getCurrency() != null ? tx.getCurrency() : "EUR";
	                    verkauf = tx.getQuantity().toPlainString();
	                    verkCur = "BTC";
	                }
	                case TRANSFER_IN -> {
	                    typ     = "Einzahlung";
	                    kauf    = tx.getQuantity().toPlainString();
	                    kaufCur = "BTC";
	                }
	                case TRANSFER_OUT -> {
	                    typ     = "Auszahlung";
	                    verkauf = tx.getQuantity().toPlainString();
	                    verkCur = "BTC";
	                }
	                default -> typ = "";
	            }

	            String datum = tx.getDate() != null
	                ? tx.getDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))
	                : "";
	            String fee         = tx.getFees()         != null ? tx.getFees().toPlainString() : "";
	            String feeCurrency = tx.getFeesCurrency() != null ? tx.getFeesCurrency()          : "";

	            printer.printRecord(typ, kauf, kaufCur, verkauf, verkCur,
	                                fee, feeCurrency, tx.getPositionLabel(),
	                                "", tx.getComment(), datum, "", "", "", "", "");
	        }
	    }
	}
    
    /** Returns the current BTC price in the given currency (0 if none is set). */
    @GetMapping("/current-price")
    public ResponseEntity<Map<String, Object>> getCurrentPriceValue(
            @RequestParam(required = false, name = "currency") String currency) {
        String cur = currency != null ? currency.toUpperCase() : "EUR";
        // only price/currency returned; Map.of() disallows nulls and priceDate isn't guaranteed to be set
        BigDecimal price = depotService.getCurrentPrice(cur)
            .map(CurrentPrice::getPrice)
            .orElse(BigDecimal.ZERO);
        return ResponseEntity.ok(Map.of(
            "price",    price,
            "currency", cur
        ));
    }

    /** Manually sets the current BTC price for a currency. */
    @PutMapping("/current-price")
    public ResponseEntity<Map<String, Object>> setCurrentPrice(
            @RequestBody CurrentPriceRequest req) {
        if (req.getPrice() == null || req.getPrice().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid price"));
        }
        String currency = req.getCurrency() != null ? req.getCurrency().toUpperCase() : "EUR";
        CurrentPrice cp = depotService.getCurrentPrice(currency).orElse(new CurrentPrice());
        cp.setTicker("BTC");
        cp.setCurrency(currency);
        cp.setPrice(req.getPrice());
        cp.setPriceDate(req.getPriceDate() != null ? req.getPriceDate() : java.time.LocalDate.now());
        cp.setLoadedAt(java.time.LocalDateTime.now());
        depotService.saveCurrentPrice(cp);
        return ResponseEntity.ok(Map.of(
            "price",    cp.getPrice(),
            "currency", cp.getCurrency(),
            "priceDate", cp.getPriceDate()
        ));
    }

    // ── App Settings ──────────────────────────────────────────────────────────

    /** Returns the app settings (currently just the tax holding-period cutoff date). */
    @GetMapping("/settings")
    public ResponseEntity<Map<String, Object>> getSettings() {
        AppSettings settings = depotService.getAppSettings();
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("taxHoldingPeriodCutoffDate", settings.getTaxHoldingPeriodCutoffDate());
        return ResponseEntity.ok(body);
    }

    /** Updates the app settings. */
    @PutMapping("/settings")
    public ResponseEntity<Map<String, Object>> updateSettings(@RequestBody AppSettingsUpdateRequest req) {
        AppSettings settings = depotService.getAppSettings();
        settings.setTaxHoldingPeriodCutoffDate(req.getTaxHoldingPeriodCutoffDate());
        depotService.saveAppSettings(settings);
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("taxHoldingPeriodCutoffDate", settings.getTaxHoldingPeriodCutoffDate());
        return ResponseEntity.ok(body);
    }

    /** Returns a single wallet/exchange position. */
    @GetMapping("/positions/{id}")
    public ResponseEntity<Map<String, Object>> getPosition(@PathVariable("id") Long id) {
        return depotService.getPosition(id)
            .map(p -> ResponseEntity.ok(Map.<String, Object>of(
                "id",    p.getId(),
                "label", p.getLabel(),
                "type",  p.getType().name()
            )))
            .orElse(ResponseEntity.notFound().build());
    }

    /** Creates a new wallet/exchange position. */
    @PostMapping("/positions")
    public ResponseEntity<Map<String, Object>> createPosition(@RequestBody PositionRequest req) {
        Position p = new Position();
        p.setLabel(req.getLabel());
        p.setType(com.thatsme4now.depot.entity.PositionType.valueOf(req.getType()));
        depotService.save(p);
        return ResponseEntity.ok(Map.of("id", p.getId(), "label", p.getLabel()));
    }

    /** Updates a wallet/exchange position's label/type. */
    @PutMapping("/positions/{id}")
    public ResponseEntity<Map<String, Object>> updatePosition(
            @PathVariable("id") Long id,
            @RequestBody PositionRequest req) {
        return depotService.getPosition(id).map(p -> {
            p.setLabel(req.getLabel());
            p.setType(com.thatsme4now.depot.entity.PositionType.valueOf(req.getType()));
            depotService.save(p);
            return ResponseEntity.ok(Map.<String, Object>of("id", p.getId(), "label", p.getLabel()));
        }).orElse(ResponseEntity.notFound().build());
    }

    /** Deletes a position, refusing if it still has transactions. */
    @DeleteMapping("/positions/{id}")
    public ResponseEntity<Map<String, Object>> deletePosition(@PathVariable("id") Long id) {
        if (depotService.getPosition(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        long txCount = depotService.getTransactionCount(id);
        if (txCount > 0) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Position has " + txCount + " transaction(s) — move or delete them first."
            ));
        }
        depotService.delete(id);
        return ResponseEntity.ok(Map.of("id", id));
    }

    /** Returns all positions with their current value and BTC balance. */
    @GetMapping("/positions")
    public List<Map<String, Object>> getPositions(HttpServletRequest request) {
    	 String currency = depotService.readCookie(request, "depot-currency", "EUR");
        return depotService.getAllPositions(currency).stream()
            .map(p -> Map.<String, Object>of(
                "id", p.getId(),
                "label", p.getLabel(),
                "totalValue", p.getTotalValue() != null ? p.getTotalValue() : BigDecimal.ZERO,
                "quantityInSats", p.getQuantityInSats() != null ? p.getQuantityInSats() : BigDecimal.ZERO))
            .collect(Collectors.toList());
    }

    /**
     * Returns portfolio-wide metrics (total value, invested, realized/unrealized
     * P/L) for the holdings metrics tile, shared with {@link HoldingsYearlyService#computePortfolioMetrics}.
     */
    @GetMapping("/metrics")
    public PortfolioMetricsDTO getMetrics(
            @RequestParam(required = false, name = "currency") String currency,
            HttpServletRequest request) {
        String cur = (currency != null && !currency.isBlank())
                ? currency
                : depotService.readCookie(request, "depot-currency", "EUR");
        return holdingsYearlyService.computePortfolioMetrics(cur);
    }

    /** Deletes the given transactions. */
    @DeleteMapping("/transactions/bulk")
    public ResponseEntity<Map<String, Object>> bulkDelete(@RequestBody List<Long> ids) {
        ids.forEach(depotService::deleteTransaction);
        return ResponseEntity.ok(Map.of("deleted", ids.size()));
    }

    /** Pairs consecutive transactions (as TRANSFER_IN/OUT pairs) by assigning each pair a shared transfer id. */
    @PostMapping("/transactions/bulk-pair")
    public ResponseEntity<Map<String, Object>> bulkPair(@RequestBody BulkPairRequest req) {
        if (req.getIds() == null || req.getIds().size() < 2 || req.getIds().size() % 2 != 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Even number of IDs required"));
        }
        int paired = 0;
        for (int i = 0; i < req.getIds().size(); i += 2) {
            String uuid = UUID.randomUUID().toString();
            for (int j = i; j < i + 2; j++) {
                depotService.getTransaction(req.getIds().get(j)).ifPresent(tx -> {
                    tx.setTransferId(uuid);
                    depotService.saveTransaction(tx);
                });
            }
            paired += 2;
        }
        return ResponseEntity.ok(Map.of("paired", paired));
    }

    /** Reassigns the given transactions to a different wallet/exchange. */
    @PostMapping("/transactions/bulk-move")
    public ResponseEntity<Map<String, Object>> bulkMove(@RequestBody BulkMoveRequest req) {
        if (req.getIds() == null || req.getTargetExchange() == null || req.getTargetExchange().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing ids or targetExchange"));
        }
        Position target = csvImportService.resolvePosition(req.getTargetExchange());
        req.getIds().forEach(id -> depotService.getTransaction(id).ifPresent(tx -> {
            tx.setPosition(target);
            depotService.saveTransaction(tx);
        }));
        return ResponseEntity.ok(Map.of("moved", req.getIds().size()));
    }

    /** Applies a new exchange rate to the given transactions. */
    @PostMapping("/transactions/bulk-exrate")
    public ResponseEntity<Map<String, Object>> bulkExRate(@RequestBody BulkExRateRequest req) {
        if (req.getIds() == null || req.getExchangeRate() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing ids or exchangeRate"));
        }
        req.getIds().forEach(id -> depotService.getTransaction(id).ifPresent(tx -> {
            tx.setExchangeRate(req.getExchangeRate());
            depotService.saveTransaction(tx);
        }));
        return ResponseEntity.ok(Map.of("updated", req.getIds().size()));
    }

    /**
     * Marks the given TRANSFER_IN/OUT transactions as solo transfers (e.g. a
     * received deposit or paid-out service with no matching counterpart) by
     * assigning each its own unique transfer id.
     */
    @PostMapping("/transactions/bulk-solo-transfer")
    public ResponseEntity<Map<String, Object>> bulkSoloTransfer(@RequestBody BulkSoloTransferRequest req) {
        if (req.getIds() == null || req.getIds().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing ids"));
        }

        // only TRANSFER_IN / TRANSFER_OUT are allowed
        for (Long id : req.getIds()) {
            Transaction tx = depotService.getTransaction(id).orElse(null);
            if (tx != null
                    && tx.getType() != TransactionType.TRANSFER_IN
                    && tx.getType() != TransactionType.TRANSFER_OUT) {
                return ResponseEntity.badRequest().body(Map.of("error", "Only TRANSFER_IN and TRANSFER_OUT allowed"));
            }
        }

        int marked = 0;
        for (Long id : req.getIds()) {
            Transaction tx = depotService.getTransaction(id).orElse(null);
            if (tx == null) continue;
            tx.setTransferId(UUID.randomUUID().toString());
            depotService.saveTransaction(tx);
            marked++;
        }
        return ResponseEntity.ok(Map.of("marked", marked));
    }

    /** Clears the transfer id on the given transactions, unpairing them. */
    @PostMapping("/transactions/bulk-remove-transfer")
    public ResponseEntity<Map<String, Object>> bulkRemoveTransfer(@RequestBody BulkRemoveTransferRequest req) {
        if (req.getIds() == null || req.getIds().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing ids"));
        }

        int removed = 0;
        for (Long id : req.getIds()) {
            Transaction tx = depotService.getTransaction(id).orElse(null);
            if (tx == null || tx.getTransferId() == null) continue;
            tx.setTransferId(null);
            depotService.saveTransaction(tx);
            removed++;
        }
        return ResponseEntity.ok(Map.of("removed", removed));
    }
    
    /** Exports the full app database (all tables) as a JSON backup, optionally AES-256-GCM encrypted. */
    @PostMapping("/export-full")
    public void exportFull(@RequestBody(required = false) ExportRequest req,
                            HttpServletResponse response) throws java.io.IOException {
        String password = (req != null && req.getPassword() != null && !req.getPassword().isBlank())
                ? req.getPassword() : null;
        byte[] data = dataExportService.exportFull(password);

        String datePart = java.time.LocalDate.now().toString(); // ISO yyyy-MM-dd
        String filename = password != null
                ? "btc-tracking_backup_" + datePart + ".json.enc"
                : "btc-tracking_backup_" + datePart + ".json";
        response.setContentType(password != null ? "application/octet-stream" : "application/json; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=" + filename);
        response.getOutputStream().write(data);
    }

    /** Restores a full JSON backup previously created by {@link #exportFull}. */
    @PostMapping(value = "/import-full", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> importFull(
            @org.springframework.web.bind.annotation.RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @org.springframework.web.bind.annotation.RequestParam(value = "password", required = false) String password) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "No file uploaded."));
            }
            DataExportService.ImportSummary summary = dataExportService.importFull(file.getBytes(), password);
            return ResponseEntity.ok(Map.of("positions", summary.positions, "transactions", summary.transactions));
        } catch (CsvEncryptionService.EncryptionException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Inner DTOs ────────────────────────────────────────────────────────────
    @lombok.Data
    public static class CurrentPriceRequest {
        private java.math.BigDecimal price;
        private java.time.LocalDate  priceDate;
        private String               currency;
    }

    @lombok.Data
    public static class AppSettingsUpdateRequest {
        private java.time.LocalDate taxHoldingPeriodCutoffDate;
    }


    @lombok.Data
    public static class MappedImportRequest {
        private List<MappedRow> rows;
    }

    @lombok.Data
    public static class MappedRow {
        private String typ;
        private String date;
        private String exchange;
        private String buyQuantity;
        private String buyCurrency;
        private String sellQuantity;
        private String sellCurrency;
        private String fee;
        private String feeCurrency;
        private String exchangeRate;
        private String comment;
        private String transactionId;
        private String transferId;
    }

    @lombok.Data
    public static class TransactionUpdateRequest {
    	private String date;
        private TransactionType type;
        private java.math.BigDecimal quantity;
        private java.math.BigDecimal quantityFiat;
        private java.math.BigDecimal fees;
        private String feesCurrency;
        private String currency;
        private java.math.BigDecimal exchangeRate;
        private String comment;
        private String exchange;
        private String transferTarget;                   // position label for the paired TRANSFER_IN
        private String transferInDate;                    // optional, defaults to date; format "yyyy-MM-dd HH:mm:ss"
        private java.math.BigDecimal transferInQuantity;  // optional, defaults to quantity
    }
    
    @lombok.Data
    public static class ExportRequest {
        private String password;
        private boolean coinTracking;
        private List<Long> ids;
    }
    
    @lombok.Data
    public static class BulkPairRequest {
        private List<Long> ids;
    }

    @lombok.Data
    public static class BulkMoveRequest {
        private List<Long> ids;
        private String targetExchange;
    }

    @lombok.Data
    public static class BulkExRateRequest {
        private List<Long> ids;
        private java.math.BigDecimal exchangeRate;
    }
    
    @lombok.Data
    public static class PositionRequest {
        private String label;
        private String type;
    }
    
    @lombok.Data
    public static class BulkSoloTransferRequest {
        private List<Long> ids;
    }
    
    @lombok.Data
    public static class BulkRemoveTransferRequest {
        private List<Long> ids;
    }
    
    @lombok.Data
    public static class BulkClearDuplicateRequest {
        private List<Long> ids;
    }

    @lombok.Data
    public static class HistoricalPriceUpdateRequest {
        private Integer year;
        private String currency;
        private java.math.BigDecimal price;
    }

    @lombok.Data
    public static class MonthlyPriceUpdateRequest {
        private Integer year;
        private Integer month;
        private String currency;
        private java.math.BigDecimal price;
    }
}