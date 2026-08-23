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
import com.thatsme4now.depot.entity.PositionAddress;
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
import com.thatsme4now.depot.service.MempoolPriceService;
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
    private final MempoolPriceService mempoolPriceService;

    // Used to convert mempool's block_time (Unix epoch, UTC) into this app's LocalDateTime "date"
    // fields when importing on-chain transactions — same zone HistoricalPriceService/MonthlyPriceService use.
    private static final java.time.ZoneId MEMPOOL_IMPORT_ZONE = java.time.ZoneId.of("Europe/Berlin");

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

    /**
     * Fills gaps in the year-end (31.12.) reference price for EUR+USD from
     * the mempool instance configured in app_settings — see
     * {@link HistoricalPriceService#fillMissingFromMempool()}. Existing
     * (seeded or manual) rows are never touched. 400 if mempool isn't
     * configured/reachable.
     */
    @PostMapping("/historical-prices/fill-missing")
    public ResponseEntity<Map<String, Object>> fillMissingHistoricalPrices() {
        try {
            return ResponseEntity.ok(historicalPriceService.fillMissingFromMempool());
        } catch (MempoolPriceService.MempoolException e) {
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

    /**
     * Fills gaps in the given year's monthly (Ultimo) reference price for
     * EUR+USD from the mempool instance configured in app_settings — see
     * {@link MonthlyPriceService#fillMissingFromMempool(int)}. Existing
     * (manual, seeded, or previously mempool-filled) rows are never
     * touched. 400 if mempool isn't configured/reachable.
     */
    @PostMapping("/monthly-prices/fill-missing")
    public ResponseEntity<Map<String, Object>> fillMissingMonthlyPrices(@RequestParam("year") int year) {
        try {
            return ResponseEntity.ok(monthlyPriceService.fillMissingFromMempool(year));
        } catch (MempoolPriceService.MempoolException e) {
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
            // Same physical on-chain transaction as the TRANSFER_OUT half — share the TXID immediately.
            txIn.setBlockchainTxId(tx.getBlockchainTxId());
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
            // Self-transfer pair: propagate the TXID to the other leg if it's still empty there.
            depotService.syncBlockchainTxIdToPairedTransfer(tx);
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
		// Unconditional (like fees/comment above), so clearing the field in the
		// UI and saving actually clears it — not just "set if provided".
		String blockchainTxId = req.getBlockchainTxId() != null ? req.getBlockchainTxId().trim() : null;
		tx.setBlockchainTxId((blockchainTxId == null || blockchainTxId.isBlank()) ? null : blockchainTxId);
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
	                           "transactionId", "blockchainTxId", "transferId")
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
	            String blockchainTxId = tx.getBlockchainTxId() != null ? tx.getBlockchainTxId()           : "";
	            String transferId     = tx.getTransferId()    != null ? tx.getTransferId()                : "";

	            printer.printRecord(typ, datum, tx.getPositionLabel(),
	                                kauf, kaufCur, verkauf, verkCur,
	                                fee, feeCurrency, exchangeRate, tx.getComment(), transactionId, blockchainTxId, transferId);
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
	            // "Tx Hash" is CoinTracking's real on-chain-TXID column — maps directly to blockchainTxId.
	            String txHash      = tx.getBlockchainTxId() != null ? tx.getBlockchainTxId()       : "";

	            printer.printRecord(typ, kauf, kaufCur, verkauf, verkCur,
	                                fee, feeCurrency, tx.getPositionLabel(),
	                                "", tx.getComment(), datum, "", "", txHash, "", "");
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
        cp.setSource("MANUAL"); // explicit manual edit always resets a prior MEMPOOL-fetched value back to MANUAL
        depotService.saveCurrentPrice(cp);
        return ResponseEntity.ok(Map.of(
            "price",    cp.getPrice(),
            "currency", cp.getCurrency(),
            "priceDate", cp.getPriceDate()
        ));
    }

    /**
     * Fetches the current BTC price from the mempool instance configured in
     * app_settings and saves it for EUR and USD (mempool returns both in one
     * call). Returns the price for the requested display currency; 400 if
     * mempool isn't configured/reachable, or if the requested currency isn't
     * one mempool supports (e.g. THB).
     */
    @PostMapping("/current-price/mempool")
    public ResponseEntity<Map<String, Object>> fetchCurrentPriceFromMempool(
            @RequestParam(required = false, name = "currency") String currency) {
        String active = currency != null ? currency.toUpperCase() : "EUR";
        try {
            Map<String, java.math.BigDecimal> prices = mempoolPriceService.fetchCurrentPrices();

            List<String> updated = new java.util.ArrayList<>();
            for (String cur : List.of("EUR", "USD")) {
                java.math.BigDecimal price = prices.get(cur);
                if (price == null) continue;
                CurrentPrice cp = depotService.getCurrentPrice(cur).orElse(new CurrentPrice());
                cp.setTicker("BTC");
                cp.setCurrency(cur);
                cp.setPrice(price);
                cp.setPriceDate(java.time.LocalDate.now());
                cp.setLoadedAt(java.time.LocalDateTime.now());
                cp.setSource("MEMPOOL");
                depotService.saveCurrentPrice(cp);
                updated.add(cur);
            }

            java.math.BigDecimal activePrice = prices.get(active);
            if (activePrice == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Währung " + active + " wird von mempool nicht unterstützt"));
            }
            return ResponseEntity.ok(Map.of(
                    "price", activePrice,
                    "currency", active,
                    "updatedCurrencies", updated
            ));
        } catch (MempoolPriceService.MempoolException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── App Settings ──────────────────────────────────────────────────────────

    /** Returns the app settings (tax holding-period cutoff date, mempool host/port). */
    @GetMapping("/settings")
    public ResponseEntity<Map<String, Object>> getSettings() {
        AppSettings settings = depotService.getAppSettings();
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("taxHoldingPeriodCutoffDate", settings.getTaxHoldingPeriodCutoffDate());
        body.put("mempoolHost", settings.getMempoolHost());
        body.put("mempoolPort", settings.getMempoolPort());
        return ResponseEntity.ok(body);
    }

    /** Updates the app settings. 400 if mempoolHost/mempoolPort are invalid (see MempoolPriceService#validateHostPort). */
    @PutMapping("/settings")
    public ResponseEntity<Map<String, Object>> updateSettings(@RequestBody AppSettingsUpdateRequest req) {
        String mempoolHost = req.getMempoolHost() != null ? req.getMempoolHost().trim() : null;
        if (mempoolHost != null && mempoolHost.isBlank()) mempoolHost = null;
        try {
            MempoolPriceService.validateHostPort(mempoolHost, req.getMempoolPort());
        } catch (MempoolPriceService.MempoolException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        AppSettings settings = depotService.getAppSettings();
        settings.setTaxHoldingPeriodCutoffDate(req.getTaxHoldingPeriodCutoffDate());
        settings.setMempoolHost(mempoolHost);
        settings.setMempoolPort(mempoolHost != null ? req.getMempoolPort() : null);
        depotService.saveAppSettings(settings);
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("taxHoldingPeriodCutoffDate", settings.getTaxHoldingPeriodCutoffDate());
        body.put("mempoolHost", settings.getMempoolHost());
        body.put("mempoolPort", settings.getMempoolPort());
        return ResponseEntity.ok(body);
    }

    /** Returns a single wallet/exchange position. */
    /** Returns a position's editable fields plus its addresses (with their last cached balance) for the edit dialog. */
    @GetMapping("/positions/{id}")
    public ResponseEntity<Map<String, Object>> getPosition(@PathVariable("id") Long id) {
        return depotService.getPosition(id)
            .map(p -> ResponseEntity.ok(Map.<String, Object>of(
                "id",          p.getId(),
                "label",       p.getLabel(),
                "type",        p.getType().name(),
                "description", p.getDescription() != null ? p.getDescription() : "",
                "addresses",   toPositionAddressList(id)
            )))
            .orElse(ResponseEntity.notFound().build());
    }

    private List<Map<String, Object>> toPositionAddressList(Long positionId) {
        return depotService.getPositionAddresses(positionId).stream()
                .map(a -> {
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("id", a.getId());
                    m.put("address", a.getAddress());
                    m.put("label", a.getLabel());
                    m.put("lastFetchBalanceSats", a.getLastFetchBalanceSats());
                    m.put("lastFetchAt", a.getLastFetchAt());
                    return m;
                })
                .collect(Collectors.toList());
    }

    /** Validates every address's format, throwing MempoolException on the first bad one. Both null and an empty list are fine (no addresses). */
    private List<DepotService.AddressInput> validateAndMapAddresses(List<PositionAddressRequest> addresses) {
        if (addresses == null) return List.of();
        List<DepotService.AddressInput> inputs = new java.util.ArrayList<>();
        for (PositionAddressRequest a : addresses) {
            MempoolPriceService.validateAddress(a.getAddress());
            inputs.add(new DepotService.AddressInput(a.getId(), a.getAddress().trim(), a.getLabel()));
        }
        return inputs;
    }

    /** Creates a new wallet/exchange position, with an optional description and address list. */
    @PostMapping("/positions")
    public ResponseEntity<Map<String, Object>> createPosition(@RequestBody PositionRequest req) {
        List<DepotService.AddressInput> addressInputs;
        try {
            addressInputs = validateAndMapAddresses(req.getAddresses());
        } catch (MempoolPriceService.MempoolException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        Position p = new Position();
        p.setLabel(req.getLabel());
        p.setType(com.thatsme4now.depot.entity.PositionType.valueOf(req.getType()));
        p.setDescription(req.getDescription());
        depotService.save(p);
        // a brand-new position has no existing addresses to diff against — any client-sent id is ignored (treated as new)
        depotService.replacePositionAddresses(p, addressInputs.stream()
                .map(a -> new DepotService.AddressInput(null, a.address(), a.label()))
                .collect(Collectors.toList()));
        return ResponseEntity.ok(Map.of("id", p.getId(), "label", p.getLabel()));
    }

    /** Updates a wallet/exchange position's label/type/description and replaces its address list. */
    @PutMapping("/positions/{id}")
    public ResponseEntity<Map<String, Object>> updatePosition(
            @PathVariable("id") Long id,
            @RequestBody PositionRequest req) {
        List<DepotService.AddressInput> addressInputs;
        try {
            addressInputs = validateAndMapAddresses(req.getAddresses());
        } catch (MempoolPriceService.MempoolException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        return depotService.getPosition(id).map(p -> {
            p.setLabel(req.getLabel());
            p.setType(com.thatsme4now.depot.entity.PositionType.valueOf(req.getType()));
            p.setDescription(req.getDescription());
            depotService.save(p);
            depotService.replacePositionAddresses(p, addressInputs);
            return ResponseEntity.ok(Map.<String, Object>of("id", p.getId(), "label", p.getLabel()));
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Fetches a single address's on-chain balance/recent transactions from the configured mempool
     * instance, persists the result as this address's new "last fetch" snapshot, and flags whether
     * the confirmed balance changed since the previous fetch and which of the returned transactions
     * are already tracked (matched by blockchain TXID, any position).
     */
    @PostMapping("/positions/{positionId}/addresses/{addressId}/mempool-balance")
    public ResponseEntity<Map<String, Object>> fetchAddressBalance(
            @PathVariable("positionId") Long positionId,
            @PathVariable("addressId") Long addressId) {
        PositionAddress addr = depotService.getPositionAddress(addressId).orElse(null);
        if (addr == null || !addr.getPosition().getId().equals(positionId)) {
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.ok(fetchAndPersistAddressBalance(addr));
        } catch (MempoolPriceService.MempoolException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Same as above, but for every address of a position at once — plus the position's own tracked BTC quantity for comparison. */
    @PostMapping("/positions/{positionId}/mempool-balance-all")
    public ResponseEntity<Map<String, Object>> fetchAllAddressBalances(@PathVariable("positionId") Long positionId) {
        Position position = depotService.getPosition(positionId).orElse(null);
        if (position == null) return ResponseEntity.notFound().build();

        List<PositionAddress> addresses = depotService.getPositionAddresses(positionId);
        List<Map<String, Object>> results = new java.util.ArrayList<>();
        List<String> errors = new java.util.ArrayList<>();
        long totalConfirmedSats = 0;
        long totalUnconfirmedSats = 0;
        for (PositionAddress addr : addresses) {
            try {
                Map<String, Object> r = fetchAndPersistAddressBalance(addr);
                r.put("addressId", addr.getId());
                r.put("address", addr.getAddress());
                r.put("label", addr.getLabel());
                results.add(r);
                totalConfirmedSats   += (Long) r.get("confirmedBalanceSats");
                totalUnconfirmedSats += (Long) r.get("unconfirmedDeltaSats");
            } catch (MempoolPriceService.MempoolException e) {
                errors.add(addr.getAddress() + ": " + e.getMessage());
            }
        }

        BigDecimal trackedQuantity = depotService.getPositionQuantity(positionId);
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("addresses", results);
        body.put("errors", errors);
        body.put("partial", !errors.isEmpty());
        body.put("totalConfirmedBalanceSats", totalConfirmedSats);
        body.put("totalUnconfirmedDeltaSats", totalUnconfirmedSats);
        body.put("trackedQuantitySats", trackedQuantity.multiply(BigDecimal.valueOf(100_000_000L)).longValue());
        return ResponseEntity.ok(body);
    }

    /**
     * Cached read of a single address's last-fetched balance/tx-list — no mempool call. Lets the
     * balance dialog show the last known state immediately on open; the live "Abrufen" button
     * still goes through the POST endpoint above. Returns {@code fetched:false} if this address
     * has never been fetched.
     */
    @GetMapping("/positions/{positionId}/addresses/{addressId}/mempool-balance")
    public ResponseEntity<Map<String, Object>> getCachedAddressBalance(
            @PathVariable("positionId") Long positionId,
            @PathVariable("addressId") Long addressId) {
        PositionAddress addr = depotService.getPositionAddress(addressId).orElse(null);
        if (addr == null || !addr.getPosition().getId().equals(positionId)) {
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.ok(cachedAddressBalance(addr));
        } catch (MempoolPriceService.MempoolException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Same as above, bundled over every address of the position — reconstructed entirely from each address's own cache, no mempool call. */
    @GetMapping("/positions/{positionId}/mempool-balance-all")
    public ResponseEntity<Map<String, Object>> getCachedAllAddressBalances(@PathVariable("positionId") Long positionId) {
        Position position = depotService.getPosition(positionId).orElse(null);
        if (position == null) return ResponseEntity.notFound().build();

        List<PositionAddress> addresses = depotService.getPositionAddresses(positionId);
        List<Map<String, Object>> results = new java.util.ArrayList<>();
        long totalConfirmedSats = 0;
        long totalUnconfirmedSats = 0;
        boolean partial = false;
        for (PositionAddress addr : addresses) {
            Map<String, Object> r = cachedAddressBalance(addr);
            r.put("addressId", addr.getId());
            r.put("address", addr.getAddress());
            r.put("label", addr.getLabel());
            results.add(r);
            if (Boolean.TRUE.equals(r.get("fetched"))) {
                totalConfirmedSats   += (Long) r.get("confirmedBalanceSats");
                totalUnconfirmedSats += (Long) r.get("unconfirmedDeltaSats");
            } else {
                partial = true;
            }
        }

        BigDecimal trackedQuantity = depotService.getPositionQuantity(positionId);
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("addresses", results);
        body.put("partial", partial);
        body.put("totalConfirmedBalanceSats", totalConfirmedSats);
        body.put("totalUnconfirmedDeltaSats", totalUnconfirmedSats);
        body.put("trackedQuantitySats", trackedQuantity.multiply(BigDecimal.valueOf(100_000_000L)).longValue());
        return ResponseEntity.ok(body);
    }

    /** Live-fetches an address's balance+recent-txs from mempool, persists the result as its new cache, and annotates the tx list. */
    private Map<String, Object> fetchAndPersistAddressBalance(PositionAddress addr) {
        MempoolPriceService.AddressInfo info = mempoolPriceService.fetchAddressInfo(addr.getAddress());
        List<MempoolPriceService.AddressTx> txs = mempoolPriceService.fetchAddressTxs(addr.getAddress(), 50);

        Long previousBalance = addr.getLastFetchBalanceSats();
        boolean changed = previousBalance != null && previousBalance != info.confirmedBalanceSats();

        LocalDateTime now = LocalDateTime.now();
        addr.setLastFetchJson(info.rawJson());
        addr.setLastFetchBalanceSats(info.confirmedBalanceSats());
        addr.setLastFetchTxsJson(mempoolPriceService.serializeAddressTxs(txs));
        addr.setLastFetchAt(now);
        depotService.savePositionAddress(addr);

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("fetched", true);
        body.put("lastFetchAt", now);
        body.put("confirmedBalanceSats", info.confirmedBalanceSats());
        body.put("unconfirmedDeltaSats", info.unconfirmedDeltaSats());
        body.put("chainTxCount", info.chainTxCount());
        body.put("changed", changed);
        body.put("previousBalanceSats", previousBalance);
        body.put("txs", annotateAddressTxs(addr.getPosition().getId(), txs));
        return body;
    }

    /** Reconstructs the same response shape as {@link #fetchAndPersistAddressBalance}, purely from the address's cache — no mempool call. */
    private Map<String, Object> cachedAddressBalance(PositionAddress addr) {
        Map<String, Object> body = new java.util.HashMap<>();
        if (addr.getLastFetchAt() == null) {
            body.put("fetched", false);
            body.put("lastFetchAt", null);
            return body;
        }
        MempoolPriceService.AddressInfo info = mempoolPriceService.parseAddressInfo(addr.getLastFetchJson(), "cache");
        List<MempoolPriceService.AddressTx> txs = mempoolPriceService.parseAddressTxs(addr.getLastFetchTxsJson());

        body.put("fetched", true);
        body.put("lastFetchAt", addr.getLastFetchAt());
        body.put("confirmedBalanceSats", info.confirmedBalanceSats());
        body.put("unconfirmedDeltaSats", info.unconfirmedDeltaSats());
        body.put("chainTxCount", info.chainTxCount());
        body.put("changed", false);
        body.put("previousBalanceSats", null);
        body.put("txs", annotateAddressTxs(addr.getPosition().getId(), txs));
        return body;
    }

    /**
     * Annotates each tx with "already tracked" (matched by TXID, any position) and, for a confirmed,
     * not-yet-tracked tx, any existing same-position TRANSFER_IN/OUT candidates it could be linked to
     * instead of imported as a new transaction (see {@link #findMatchCandidates}). Both are always
     * recomputed fresh against the current Transaction table — cheap local DB checks, never cached —
     * so they stay correct even when rendering a cached balance/tx-list.
     */
    private List<Map<String, Object>> annotateAddressTxs(Long positionId, List<MempoolPriceService.AddressTx> txs) {
        return txs.stream().map(tx -> {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("txid", tx.txid());
            m.put("confirmed", tx.confirmed());
            m.put("blockTime", tx.blockTimeEpochSeconds());
            m.put("netSats", tx.netSats());
            boolean alreadyTracked = depotService.transactionExistsByBlockchainTxId(tx.txid());
            m.put("alreadyTracked", alreadyTracked);
            m.put("matchCandidates", (!alreadyTracked && tx.confirmed() && tx.blockTimeEpochSeconds() != null)
                    ? findMatchCandidates(positionId, tx.netSats(), tx.blockTimeEpochSeconds())
                    : List.of());
            return m;
        }).collect(Collectors.toList());
    }

    /** Existing TRANSFER_IN/OUT transactions (same position, no TXID yet) that could be this on-chain tx — see TransactionRepository#findByPositionIdAndTypeAndBlockchainTxIdIsNullAndQuantityAndDateBetween. */
    private List<Map<String, Object>> findMatchCandidates(Long positionId, long netSats, long blockTimeEpochSeconds) {
        TransactionType type = netSats >= 0 ? TransactionType.TRANSFER_IN : TransactionType.TRANSFER_OUT;
        BigDecimal quantity = BigDecimal.valueOf(Math.abs(netSats)).divide(BigDecimal.valueOf(100_000_000L), 8, java.math.RoundingMode.UNNECESSARY);
        // "Same calendar day" tolerance (Europe/Berlin): block_time is second-exact, a manually
        // entered transaction usually isn't — see DepotRestController class-level MEMPOOL_IMPORT_ZONE.
        java.time.LocalDate day = java.time.Instant.ofEpochSecond(blockTimeEpochSeconds).atZone(MEMPOOL_IMPORT_ZONE).toLocalDate();
        LocalDateTime dayStart = day.atStartOfDay();
        LocalDateTime dayEnd = day.atTime(23, 59, 59);
        return depotService.findMatchCandidates(positionId, type, quantity, dayStart, dayEnd).stream()
                .map(t -> {
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("id", t.getId());
                    m.put("date", t.getDate());
                    m.put("quantity", t.getQuantity());
                    m.put("comment", t.getComment());
                    return m;
                })
                .collect(Collectors.toList());
    }

    /**
     * Links an on-chain TXID to an existing TRANSFER_IN/OUT transaction (one of the match candidates
     * from {@link #findMatchCandidates}) instead of importing it as a new transaction — only the
     * blockchainTxId is set, every other field of the existing transaction is left untouched.
     */
    @PostMapping("/positions/{positionId}/addresses/{addressId}/mempool-link")
    public ResponseEntity<Map<String, Object>> linkAddressTransaction(
            @PathVariable("positionId") Long positionId,
            @PathVariable("addressId") Long addressId,
            @RequestBody AddressLinkRequest req) {
        PositionAddress addr = depotService.getPositionAddress(addressId).orElse(null);
        if (addr == null || !addr.getPosition().getId().equals(positionId)) {
            return ResponseEntity.notFound().build();
        }
        if (req.getTxid() == null || req.getTxid().isBlank() || req.getExistingTransactionId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing txid/existingTransactionId"));
        }
        Transaction tx = depotService.getTransaction(req.getExistingTransactionId()).orElse(null);
        if (tx == null || !tx.getPosition().getId().equals(positionId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Transaktion nicht gefunden"));
        }
        if (tx.getType() != TransactionType.TRANSFER_IN && tx.getType() != TransactionType.TRANSFER_OUT) {
            return ResponseEntity.badRequest().body(Map.of("error", "Nur TRANSFER_IN/TRANSFER_OUT können verknüpft werden"));
        }
        if (tx.getBlockchainTxId() != null && !tx.getBlockchainTxId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Transaktion hat bereits eine TXID"));
        }
        if (depotService.transactionExistsByBlockchainTxId(req.getTxid())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Diese TXID ist bereits einer anderen Transaktion zugeordnet"));
        }
        tx.setBlockchainTxId(req.getTxid());
        depotService.saveTransaction(tx);
        // Self-transfer pair: propagate the TXID to the other leg if it's still empty there — same as a manual edit (see updateTransaction).
        depotService.syncBlockchainTxIdToPairedTransfer(tx);
        return ResponseEntity.ok(Map.of("linked", true, "transactionId", tx.getId()));
    }

    /** Imports the given (confirmed, not-yet-tracked) TXIDs from an address's last-fetched recent transactions as new TRANSFER_IN/OUT transactions. */
    @PostMapping("/positions/{positionId}/addresses/{addressId}/mempool-import")
    public ResponseEntity<Map<String, Object>> importAddressTransactions(
            @PathVariable("positionId") Long positionId,
            @PathVariable("addressId") Long addressId,
            @RequestBody AddressImportRequest req) {
        PositionAddress addr = depotService.getPositionAddress(addressId).orElse(null);
        if (addr == null || !addr.getPosition().getId().equals(positionId)) {
            return ResponseEntity.notFound().build();
        }
        if (req.getTxids() == null || req.getTxids().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing txids"));
        }
        try {
            // re-fetch rather than trusting client-supplied amounts/dates — the client only sends which txids it wants
            List<MempoolPriceService.AddressTx> txs = mempoolPriceService.fetchAddressTxs(addr.getAddress(), 50);
            java.util.Set<String> requested = new java.util.HashSet<>(req.getTxids());
            int imported = 0;
            List<String> skipped = new java.util.ArrayList<>();
            for (MempoolPriceService.AddressTx tx : txs) {
                if (!requested.contains(tx.txid())) continue;
                // unconfirmed (no block_time yet) and already-tracked txs are silently skipped —
                // the UI only offers confirmed, not-yet-tracked rows as selectable in the first place
                if (!tx.confirmed() || tx.blockTimeEpochSeconds() == null
                        || depotService.transactionExistsByBlockchainTxId(tx.txid())) {
                    skipped.add(tx.txid());
                    continue;
                }
                Transaction t = new Transaction();
                t.setPosition(addr.getPosition());
                t.setType(tx.netSats() >= 0 ? TransactionType.TRANSFER_IN : TransactionType.TRANSFER_OUT);
                t.setDate(LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(tx.blockTimeEpochSeconds()), MEMPOOL_IMPORT_ZONE));
                t.setQuantity(BigDecimal.valueOf(Math.abs(tx.netSats())).divide(BigDecimal.valueOf(100_000_000L), 8, java.math.RoundingMode.UNNECESSARY));
                t.setExchangeRate(BigDecimal.ONE);
                t.setTransactionId(UUID.randomUUID().toString());
                t.setBlockchainTxId(tx.txid());
                // no matching counterpart known — flagged as a solo transfer, same as the existing "Solo-Transfer" bulk action
                t.setTransferId(UUID.randomUUID().toString());
                depotService.saveTransaction(t);
                imported++;
            }
            return ResponseEntity.ok(Map.of("imported", imported, "skipped", skipped));
        } catch (MempoolPriceService.MempoolException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
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
        private String  mempoolHost;
        private Integer mempoolPort;
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
        private String blockchainTxId;    // real on-chain BTC TXID, optional CSV mapping (TRANSFER_IN/TRANSFER_OUT only)
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
        private String blockchainTxId;                   // real on-chain BTC TXID, TRANSFER_IN/TRANSFER_OUT only, soft-validated (see tx-form.js)
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
        private String description;
        private List<PositionAddressRequest> addresses;
    }

    @lombok.Data
    public static class PositionAddressRequest {
        /** Null for a new address; set to an existing position_address.id to update it in place. */
        private Long id;
        private String address;
        private String label;
    }

    @lombok.Data
    public static class AddressImportRequest {
        private List<String> txids;
    }

    @lombok.Data
    public static class AddressLinkRequest {
        private String txid;
        /** Transaction#id (DB primary key) of the existing, TXID-less transaction to link — not to be confused with Transaction#transactionId (the String business key). */
        private Long existingTransactionId;
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