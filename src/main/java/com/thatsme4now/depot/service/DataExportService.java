package com.thatsme4now.depot.service;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.thatsme4now.depot.dto.CurrentPriceExportDTO;
import com.thatsme4now.depot.dto.FullExportDTO;
import com.thatsme4now.depot.dto.HistoricalPriceExportDTO;
import com.thatsme4now.depot.dto.ImportHistoryExportDTO;
import com.thatsme4now.depot.dto.MonthlyPriceExportDTO;
import com.thatsme4now.depot.dto.PositionExportDTO;
import com.thatsme4now.depot.dto.PriceHistoryExportDTO;
import com.thatsme4now.depot.dto.TransactionExportDTO;
import com.thatsme4now.depot.entity.CurrentPrice;
import com.thatsme4now.depot.entity.HistoricalPrice;
import com.thatsme4now.depot.entity.ImportHistory;
import com.thatsme4now.depot.entity.MonthlyPrice;
import com.thatsme4now.depot.entity.Position;
import com.thatsme4now.depot.entity.PriceHistory;
import com.thatsme4now.depot.entity.Transaction;
import com.thatsme4now.depot.repository.CurrentPriceRepository;
import com.thatsme4now.depot.repository.HistoricalPriceRepository;
import com.thatsme4now.depot.repository.ImportHistoryRepository;
import com.thatsme4now.depot.repository.MonthlyPriceRepository;
import com.thatsme4now.depot.repository.PositionRepository;
import com.thatsme4now.depot.repository.PriceHistoryRepository;
import com.thatsme4now.depot.repository.TransactionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Create backup" / "Restore backup" (formerly DB export/import) — a complete
 * snapshot of the application data as JSON, optionally password-encrypted.
 *
 * Since export format version 2, the backup covers position/transaction plus
 * import_history and the four price tables (price_history, current_price,
 * historical_price, monthly_price) — see {@link FullExportDTO}. Each section
 * is deliberately nullable: if a section is missing from the restored file
 * (e.g. a backup from before this update), the corresponding table is left
 * untouched during restore instead of being cleared (see {@link #importFull}).
 *
 * import_staging_row (the working state of an in-progress import) is deliberately
 * NOT part of the backup — it's purely transient and is cleared on every new upload anyway.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataExportService {

    private final PositionRepository positionRepo;
    private final TransactionRepository transactionRepo;
    private final ImportHistoryRepository importHistoryRepo;
    private final PriceHistoryRepository priceHistoryRepo;
    private final CurrentPriceRepository currentPriceRepo;
    private final HistoricalPriceRepository historicalPriceRepo;
    private final MonthlyPriceRepository monthlyPriceRepo;
    private final CsvEncryptionService encryptionService;
    private final JdbcTemplate jdbcTemplate;

    private final ObjectMapper mapper = buildMapper();

    private static ObjectMapper buildMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        m.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        m.enable(SerializationFeature.INDENT_OUTPUT);
        return m;
    }

    // ── Export ──────────────────────────────────────────────

    /** Serializes all app data to JSON, encrypting it with the password if given. */
    public byte[] exportFull(String password) {
        FullExportDTO dto = new FullExportDTO();
        dto.setExportedAt(LocalDateTime.now());
        dto.setPositions(positionRepo.findAll().stream()
                .map(this::toDto).collect(Collectors.toList()));
        dto.setTransactions(transactionRepo.findAll().stream()
                .map(this::toDto).collect(Collectors.toList()));
        dto.setImportHistory(importHistoryRepo.findAll().stream()
                .map(this::toDto).collect(Collectors.toList()));
        dto.setPriceHistory(priceHistoryRepo.findAll().stream()
                .map(this::toDto).collect(Collectors.toList()));
        dto.setCurrentPrices(currentPriceRepo.findAll().stream()
                .map(this::toDto).collect(Collectors.toList()));
        dto.setHistoricalPrices(historicalPriceRepo.findAll().stream()
                .map(this::toDto).collect(Collectors.toList()));
        dto.setMonthlyPrices(monthlyPriceRepo.findAll().stream()
                .map(this::toDto).collect(Collectors.toList()));

        try {
            byte[] json = mapper.writeValueAsBytes(dto);
            return (password != null && !password.isBlank())
                    ? encryptionService.encrypt(json, password)
                    : json;
        } catch (Exception e) {
            throw new RuntimeException("Full export failed: " + e.getMessage(), e);
        }
    }

    // ── Import (init process: DELETE ALL, then insert with original IDs) ────

    /** Restores a full backup: decrypts if needed, wipes all covered tables, and reinserts with original IDs. */
    @Transactional
    public ImportSummary importFull(byte[] fileBytes, String password) {
        byte[] json = (password != null && !password.isBlank())
                ? encryptionService.decrypt(fileBytes, password)
                : fileBytes;

        FullExportDTO dto;
        try {
            dto = mapper.readValue(json, FullExportDTO.class);
        } catch (Exception e) {
            throw new RuntimeException("Invalid export file: " + e.getMessage(), e);
        }

        if (dto.getPositions() == null || dto.getTransactions() == null) {
            throw new RuntimeException("Export file missing positions or transactions.");
        }

        // Order matters: transaction has a real FK to position (ON DELETE CASCADE)
        // plus a loose (non-FK) reference to import_history — so position and
        // import_history must be cleared/refilled before transaction.
        jdbcTemplate.update("DELETE FROM `transaction`");
        jdbcTemplate.update("DELETE FROM `position`");

        long maxPositionId = 0;
        for (PositionExportDTO p : dto.getPositions()) {
            jdbcTemplate.update(
                "INSERT INTO `position` (id, label, type, created_at, updated_at) VALUES (?, ?, ?, NOW(), NOW())",
                p.getId(), p.getLabel(), p.getType().name());
            maxPositionId = Math.max(maxPositionId, p.getId());
        }
        jdbcTemplate.update("ALTER TABLE `position` AUTO_INCREMENT = " + (maxPositionId + 1));

        boolean importHistoryRestored = restoreImportHistory(dto.getImportHistory());

        long maxTransactionId = 0;
        for (TransactionExportDTO t : dto.getTransactions()) {
            jdbcTemplate.update(
                "INSERT INTO `transaction` (id, transaction_id, position_id, type, date, quantity, " +
                "quantity_fiat, currency, exchange_rate, price_per_btc, fees, fees_currency, comment, " +
                "transfer_id, is_duplicate, import_history_id, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW())",
                t.getId(), t.getTransactionId(), t.getPositionId(), t.getType().name(), t.getDate(),
                t.getQuantity(), t.getQuantityFiat(), t.getCurrency(), t.getExchangeRate(),
                t.getPricePerBtc(), t.getFees(), t.getFeesCurrency(), t.getComment(),
                t.getTransferId(), t.isDuplicate(), t.getImportHistoryId());
            maxTransactionId = Math.max(maxTransactionId, t.getId());
        }
        // NOTE: MySQL/H2 don't allow bind params in DDL → inline the (internally computed) value.
        jdbcTemplate.update("ALTER TABLE `transaction` AUTO_INCREMENT = " + (maxTransactionId + 1));

        int priceHistoryCount   = restorePriceHistory(dto.getPriceHistory());
        int currentPriceCount   = restoreCurrentPrices(dto.getCurrentPrices());
        int historicalPriceCount = restoreHistoricalPrices(dto.getHistoricalPrices());
        int monthlyPriceCount   = restoreMonthlyPrices(dto.getMonthlyPrices());

        log.info("Backup restore: {} positions, {} transactions, import-history restored={}, " +
                 "{} price-history, {} current-price, {} historical-price, {} monthly-price rows",
                 dto.getPositions().size(), dto.getTransactions().size(), importHistoryRestored,
                 priceHistoryCount, currentPriceCount, historicalPriceCount, monthlyPriceCount);

        ImportSummary summary = new ImportSummary();
        summary.positions = dto.getPositions().size();
        summary.transactions = dto.getTransactions().size();
        return summary;
    }

    /** @return false if the section was missing from the backup (table is left untouched). */
    private boolean restoreImportHistory(java.util.List<ImportHistoryExportDTO> rows) {
        if (rows == null) return false; // old backup without this section -> leave the table untouched
        jdbcTemplate.update("DELETE FROM import_history");
        long maxId = 0;
        for (ImportHistoryExportDTO h : rows) {
            jdbcTemplate.update(
                "INSERT INTO import_history (id, imported_at, filename, total_rows, imported_rows, " +
                "duplicate_rows, error_rows) VALUES (?,?,?,?,?,?,?)",
                h.getId(), h.getImportedAt(), h.getFilename(), h.getTotalRows(), h.getImportedRows(),
                h.getDuplicateRows(), h.getErrorRows());
            maxId = Math.max(maxId, h.getId());
        }
        jdbcTemplate.update("ALTER TABLE import_history AUTO_INCREMENT = " + (maxId + 1));
        return true;
    }

    private int restorePriceHistory(java.util.List<PriceHistoryExportDTO> rows) {
        if (rows == null) return 0;
        jdbcTemplate.update("DELETE FROM price_history");
        long maxId = 0;
        for (PriceHistoryExportDTO p : rows) {
            jdbcTemplate.update(
                "INSERT INTO price_history (id, ticker, date, open, high, low, close, volume, loaded_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?)",
                p.getId(), p.getTicker(), p.getDate(), p.getOpen(), p.getHigh(), p.getLow(),
                p.getClose(), p.getVolume(), p.getLoadedAt());
            maxId = Math.max(maxId, p.getId());
        }
        jdbcTemplate.update("ALTER TABLE price_history AUTO_INCREMENT = " + (maxId + 1));
        return rows.size();
    }

    private int restoreCurrentPrices(java.util.List<CurrentPriceExportDTO> rows) {
        if (rows == null) return 0;
        jdbcTemplate.update("DELETE FROM current_price");
        for (CurrentPriceExportDTO c : rows) {
            jdbcTemplate.update(
                "INSERT INTO current_price (ticker, currency, price, price_date, loaded_at) VALUES (?,?,?,?,?)",
                c.getTicker(), c.getCurrency(), c.getPrice(), c.getPriceDate(), c.getLoadedAt());
        }
        return rows.size(); // no auto-increment (composite PK ticker+currency) -> no ALTER TABLE needed
    }

    private int restoreHistoricalPrices(java.util.List<HistoricalPriceExportDTO> rows) {
        if (rows == null) return 0;
        jdbcTemplate.update("DELETE FROM historical_price");
        long maxId = 0;
        for (HistoricalPriceExportDTO h : rows) {
            jdbcTemplate.update(
                "INSERT INTO historical_price (id, ticker, price_year, currency, price) VALUES (?,?,?,?,?)",
                h.getId(), h.getTicker(), h.getYear(), h.getCurrency(), h.getPrice());
            maxId = Math.max(maxId, h.getId());
        }
        jdbcTemplate.update("ALTER TABLE historical_price AUTO_INCREMENT = " + (maxId + 1));
        return rows.size();
    }

    private int restoreMonthlyPrices(java.util.List<MonthlyPriceExportDTO> rows) {
        if (rows == null) return 0;
        jdbcTemplate.update("DELETE FROM monthly_price");
        long maxId = 0;
        for (MonthlyPriceExportDTO m : rows) {
            jdbcTemplate.update(
                "INSERT INTO monthly_price (id, ticker, price_year, price_month, currency, price) VALUES (?,?,?,?,?,?)",
                m.getId(), m.getTicker(), m.getYear(), m.getMonth(), m.getCurrency(), m.getPrice());
            maxId = Math.max(maxId, m.getId());
        }
        jdbcTemplate.update("ALTER TABLE monthly_price AUTO_INCREMENT = " + (maxId + 1));
        return rows.size();
    }

    // ── toDto Mapper ──────────────────────────────────────────

    private PositionExportDTO toDto(Position p) {
        PositionExportDTO dto = new PositionExportDTO();
        dto.setId(p.getId());
        dto.setLabel(p.getLabel());
        dto.setType(p.getType());
        return dto;
    }

    private TransactionExportDTO toDto(Transaction tx) {
        TransactionExportDTO dto = new TransactionExportDTO();
        dto.setId(tx.getId());
        dto.setTransactionId(tx.getTransactionId());
        dto.setPositionId(tx.getPosition().getId());
        dto.setType(tx.getType());
        dto.setDate(tx.getDate());
        dto.setQuantity(tx.getQuantity());
        dto.setQuantityFiat(tx.getQuantityFiat());
        dto.setPricePerBtc(tx.getPricePerBtc());
        dto.setFees(tx.getFees());
        dto.setFeesCurrency(tx.getFeesCurrency());
        dto.setCurrency(tx.getCurrency());
        dto.setExchangeRate(tx.getExchangeRate());
        dto.setTransferId(tx.getTransferId());
        dto.setDuplicate(tx.isDuplicate());
        dto.setComment(tx.getComment());
        dto.setImportHistoryId(tx.getImportHistoryId());
        return dto;
    }

    private ImportHistoryExportDTO toDto(ImportHistory h) {
        ImportHistoryExportDTO dto = new ImportHistoryExportDTO();
        dto.setId(h.getId());
        dto.setImportedAt(h.getImportedAt());
        dto.setFilename(h.getFilename());
        dto.setTotalRows(h.getTotalRows());
        dto.setImportedRows(h.getImportedRows());
        dto.setDuplicateRows(h.getDuplicateRows());
        dto.setErrorRows(h.getErrorRows());
        return dto;
    }

    private PriceHistoryExportDTO toDto(PriceHistory p) {
        PriceHistoryExportDTO dto = new PriceHistoryExportDTO();
        dto.setId(p.getId());
        dto.setTicker(p.getTicker());
        dto.setDate(p.getDate());
        dto.setOpen(p.getOpen());
        dto.setHigh(p.getHigh());
        dto.setLow(p.getLow());
        dto.setClose(p.getClose());
        dto.setVolume(p.getVolume());
        dto.setLoadedAt(p.getLoadedAt());
        return dto;
    }

    private CurrentPriceExportDTO toDto(CurrentPrice c) {
        CurrentPriceExportDTO dto = new CurrentPriceExportDTO();
        dto.setTicker(c.getTicker());
        dto.setCurrency(c.getCurrency());
        dto.setPrice(c.getPrice());
        dto.setPriceDate(c.getPriceDate());
        dto.setLoadedAt(c.getLoadedAt());
        return dto;
    }

    private HistoricalPriceExportDTO toDto(HistoricalPrice h) {
        HistoricalPriceExportDTO dto = new HistoricalPriceExportDTO();
        dto.setId(h.getId());
        dto.setTicker(h.getTicker());
        dto.setYear(h.getYear());
        dto.setCurrency(h.getCurrency());
        dto.setPrice(h.getPrice());
        return dto;
    }

    private MonthlyPriceExportDTO toDto(MonthlyPrice m) {
        MonthlyPriceExportDTO dto = new MonthlyPriceExportDTO();
        dto.setId(m.getId());
        dto.setTicker(m.getTicker());
        dto.setYear(m.getYear());
        dto.setMonth(m.getMonth());
        dto.setCurrency(m.getCurrency());
        dto.setPrice(m.getPrice());
        return dto;
    }

    public static class ImportSummary {
        public int positions;
        public int transactions;
    }

    // ── Clear (for app lock) ───────────────────────────────────
    // Must match the scope of exportFull() 1:1 — otherwise locking would leave
    // leftover unencrypted data in the DB (see AppLockService#lock: it snapshots
    // via exportFull() first, then clears everything here).

    /** Deletes all data covered by {@link #exportFull} — used when locking the app. */
    @Transactional
    public void clearAll() {
        jdbcTemplate.update("DELETE FROM `transaction`");
        jdbcTemplate.update("DELETE FROM `position`");
        jdbcTemplate.update("DELETE FROM import_history");
        jdbcTemplate.update("DELETE FROM price_history");
        jdbcTemplate.update("DELETE FROM current_price");
        jdbcTemplate.update("DELETE FROM historical_price");
        jdbcTemplate.update("DELETE FROM monthly_price");
    }
}
