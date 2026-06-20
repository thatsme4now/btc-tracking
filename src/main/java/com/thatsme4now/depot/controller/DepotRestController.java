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

import com.thatsme4now.depot.dto.TransactionDTO;
import com.thatsme4now.depot.entity.CurrentPrice;
import com.thatsme4now.depot.entity.Position;
import com.thatsme4now.depot.entity.PriceHistory;
import com.thatsme4now.depot.entity.Transaction;
import com.thatsme4now.depot.entity.TransactionType;
import com.thatsme4now.depot.service.CsvEncryptionService;
import com.thatsme4now.depot.service.CsvImportService;
import com.thatsme4now.depot.service.DepotService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/btc-tracking")
@RequiredArgsConstructor
public class DepotRestController {

    private final DepotService     depotService;
    private final CsvImportService csvImportService;
    private final CsvEncryptionService csvEncryptionService;

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(
            @RequestParam(name = "currency", defaultValue = "EUR") String currency) {
        int totalNew = depotService.refreshPrices(currency);
        return ResponseEntity.ok(Map.of("totalNew", totalNew));
    }

    @GetMapping("/history")
    public List<PriceHistory> getHistory() {
        return depotService.getHistory();
    }

    // ── Mapped import from PapaParse frontend ─────────────────────────────────
    @PostMapping("/import-mapped")
    public ResponseEntity<Map<String, Object>> importMapped(
            @RequestBody MappedImportRequest req) {
        try {
            int inserted = csvImportService.importMapped(req.getRows());
            return ResponseEntity.ok(Map.of("inserted", inserted));
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
                               "fee", "feeCurrency", "exchangeRate", "comment", "transactionId")
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
                    rows.add(r);
                }
            }
 
            int inserted = csvImportService.importMapped(rows);
            return ResponseEntity.ok(Map.of("inserted", inserted));
 
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Transactions CRUD ─────────────────────────────────────────────────────

    @GetMapping("/transactions")
    public List<TransactionDTO> getAllTransactions() {
        return depotService.getAllTransactions();
    }
    
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
        // Pairing: wenn TRANSFER_OUT + transferTarget gesetzt → UUID vergeben
        if (tx.getType() == TransactionType.TRANSFER_OUT
                && req.getTransferTarget() != null
                && !req.getTransferTarget().isBlank()) {

            String uuid = UUID.randomUUID().toString();
            tx.setTransferId(uuid);
            Transaction txIn = new Transaction();
            txIn.setType(TransactionType.TRANSFER_IN);
            txIn.setDate(req.getTransferInDate() != null ? req.getTransferInDate() : tx.getDate());
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
    
	@DeleteMapping("/")
    public ResponseEntity<Void> deleteAllTransaction() {
        depotService.deleteTransaction();
        depotService.delete();
        return ResponseEntity.noContent().build();
    }
	
	@DeleteMapping("/transactions/{id}")
    public ResponseEntity<Void> deleteTransaction(@PathVariable("id") Long id) {
        depotService.deleteTransaction(id);
        return ResponseEntity.noContent().build();
    }


	/**
     * POST /api/depot/export
     * Body (JSON): { "password": "optional-secret" }
     *
     * - password null/blank → plain CSV (transactions_export.csv)
     * - password present    → AES-256-GCM encrypted (transactions_export.enc)
     */
    @PostMapping("/export")
    public void exportCsv(
            @RequestBody(required = false) ExportRequest req,
            HttpServletResponse response) throws java.io.IOException {
 
        String password = (req != null && req.getPassword() != null
                           && !req.getPassword().isBlank())
                          ? req.getPassword() : null;
 
        // ── Build CSV in memory ───────────────────────────────────────────────
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
 
        // UTF-8 BOM
        baos.write(0xEF); baos.write(0xBB); baos.write(0xBF);
 
        try (org.apache.commons.csv.CSVPrinter printer = new org.apache.commons.csv.CSVPrinter(
                new java.io.OutputStreamWriter(baos, java.nio.charset.StandardCharsets.UTF_8),
                org.apache.commons.csv.CSVFormat.DEFAULT.builder()
                    .setHeader("typ", "date", "exchange",
                               "buyQty", "buyCur",
                               "sellQty", "sellCur",
                               "fee", "feeCur", "exchangeRate", "comment",
                               "transactionId")
                    .setDelimiter(",")
                    .setQuote('"')
                    .setQuoteMode(org.apache.commons.csv.QuoteMode.ALL)
                    .build())) {
 
            for (TransactionDTO tx : depotService.getAllTransactions()) {
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
//                String datum = "";
                String fee          = tx.getFees()         != null ? tx.getFees().toPlainString()         : "";
                String feeCurrency  = tx.getFeesCurrency() != null ? tx.getFeesCurrency()                 : "";
                String exchangeRate = tx.getExchangeRate() != null ? tx.getExchangeRate().toPlainString() : "";
                String transactionId  = tx.getTransactionId() != null ? tx.getTransactionId()             : "";

                printer.printRecord(typ, datum, tx.getPositionLabel(),
                                    kauf, kaufCur, verkauf, verkCur,
                                    fee, feeCurrency, exchangeRate, tx.getComment(), transactionId);
            }
        }
 
        byte[] csvBytes = baos.toByteArray();
 
        // ── Encrypt or serve plain ────────────────────────────────────────────
        if (password != null) {
            byte[] encBytes = csvEncryptionService.encrypt(csvBytes, password);
            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition",
                               "attachment; filename=transactions_export.enc");
            response.getOutputStream().write(encBytes);
        } else {
            response.setContentType("text/csv; charset=UTF-8");
            response.setHeader("Content-Disposition",
                               "attachment; filename=transactions_export.csv");
            response.getOutputStream().write(csvBytes);
        }
    }
    
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

    @PostMapping("/positions")
    public ResponseEntity<Map<String, Object>> createPosition(@RequestBody PositionRequest req) {
        Position p = new Position();
        p.setLabel(req.getLabel());
        p.setType(com.thatsme4now.depot.entity.PositionType.valueOf(req.getType()));
        depotService.save(p);
        return ResponseEntity.ok(Map.of("id", p.getId(), "label", p.getLabel()));
    }

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

    @GetMapping("/positions")
    public List<Map<String, Object>> getPositions(HttpServletRequest request) {
    	 String currency = depotService.readCookie(request, "depot-currency", "EUR");
        return depotService.getAllPositions(currency).stream()
            .map(p -> Map.<String, Object>of("id", p.getId(), "label", p.getLabel()))
            .collect(Collectors.toList());
    }
    
    @DeleteMapping("/transactions/bulk")
    public ResponseEntity<Map<String, Object>> bulkDelete(@RequestBody List<Long> ids) {
        ids.forEach(depotService::deleteTransaction);
        return ResponseEntity.ok(Map.of("deleted", ids.size()));
    }

    // Bulk Transfer Pairing
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

    // Bulk Move Position
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

    // Bulk Exchange Rate
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


    // ── Inner DTOs ────────────────────────────────────────────────────────────
    @lombok.Data
    public static class CurrentPriceRequest {
        private java.math.BigDecimal price;
        private java.time.LocalDate  priceDate;
        private String               currency;
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
    }

    @lombok.Data
    public static class TransactionUpdateRequest {
//        private java.time.LocalDateTime date;
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
        private String transferTarget;                    // Position-Label für TRANSFER_IN
        private java.time.LocalDateTime transferInDate;   // optional, sonst = date
        private java.math.BigDecimal transferInQuantity;  // optional, sonst = quantity
    }
    
    @lombok.Data
    public static class ExportRequest {
        private String password;
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
}