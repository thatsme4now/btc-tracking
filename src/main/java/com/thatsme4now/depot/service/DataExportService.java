package com.thatsme4now.depot.service;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.thatsme4now.depot.dto.FullExportDTO;
import com.thatsme4now.depot.dto.PositionExportDTO;
import com.thatsme4now.depot.dto.TransactionExportDTO;
import com.thatsme4now.depot.entity.Position;
import com.thatsme4now.depot.entity.Transaction;
import com.thatsme4now.depot.repository.PositionRepository;
import com.thatsme4now.depot.repository.TransactionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataExportService {

    private final PositionRepository positionRepo;
    private final TransactionRepository transactionRepo;
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

    public byte[] exportFull(String password) {
        FullExportDTO dto = new FullExportDTO();
        dto.setExportedAt(LocalDateTime.now());
        dto.setPositions(positionRepo.findAll().stream()
                .map(this::toDto).collect(Collectors.toList()));
        dto.setTransactions(transactionRepo.findAll().stream()
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

    // ── Import (Init-Process: DELETE ALL, then insert with original IDs) ────

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

        jdbcTemplate.update("DELETE FROM `transaction`");
        jdbcTemplate.update("DELETE FROM `position`");

        long maxPositionId = 0;
        for (PositionExportDTO p : dto.getPositions()) {
            jdbcTemplate.update(
                "INSERT INTO `position` (id, label, type, created_at, updated_at) VALUES (?, ?, ?, NOW(), NOW())",
                p.getId(), p.getLabel(), p.getType().name());
            maxPositionId = Math.max(maxPositionId, p.getId());
        }

        long maxTransactionId = 0;
        for (TransactionExportDTO t : dto.getTransactions()) {
            jdbcTemplate.update(
                "INSERT INTO `transaction` (id, transaction_id, position_id, type, date, quantity, " +
                "quantity_fiat, currency, exchange_rate, price_per_btc, fees, fees_currency, comment, " +
                "transfer_id, is_duplicate, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW())",
                t.getId(), t.getTransactionId(), t.getPositionId(), t.getType().name(), t.getDate(),
                t.getQuantity(), t.getQuantityFiat(), t.getCurrency(), t.getExchangeRate(),
                t.getPricePerBtc(), t.getFees(), t.getFeesCurrency(), t.getComment(),
                t.getTransferId(), t.isDuplicate());
            maxTransactionId = Math.max(maxTransactionId, t.getId());
        }

        // NOTE: MySQL/H2 don't allow bind params in DDL → inline the (internally computed) value.
        jdbcTemplate.update("ALTER TABLE `position` AUTO_INCREMENT = " + (maxPositionId + 1));
        jdbcTemplate.update("ALTER TABLE `transaction` AUTO_INCREMENT = " + (maxTransactionId + 1));

        ImportSummary summary = new ImportSummary();
        summary.positions = dto.getPositions().size();
        summary.transactions = dto.getTransactions().size();
        return summary;
    }

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
        return dto;
    }

    public static class ImportSummary {
        public int positions;
        public int transactions;
    }
}