package com.thatsme4now.depot.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.thatsme4now.depot.BaseIntegrationTest;
import com.thatsme4now.depot.TestFixtures;
import com.thatsme4now.depot.entity.Position;
import com.thatsme4now.depot.entity.PositionType;
import com.thatsme4now.depot.service.DataExportService.ImportSummary;

/**
 * {@link DataExportService} runs some of its restore logic via raw JDBC
 * (including ALTER TABLE ... AUTO_INCREMENT), which may not fully participate
 * in the test's transaction rollback under H2 — so this class defensively
 * clears all data after every test rather than relying on rollback alone.
 */
class DataExportServiceTest extends BaseIntegrationTest {

    @Autowired
    private DataExportService dataExportService;

    @Autowired
    private DepotService depotService;

    @AfterEach
    void cleanUp() {
        dataExportService.clearAll();
    }

    @Test
    void exportFull_thenImportFull_unencrypted_roundTripsData() {
        Position p = depotService.save(TestFixtures.position("Binance", PositionType.EXCHANGE));
        depotService.saveTransaction(TestFixtures.buy(p, LocalDateTime.now().minusDays(1),
                new BigDecimal("0.01"), new BigDecimal("50000"), "EUR"));
        depotService.saveCurrentPrice(TestFixtures.currentPrice("EUR", new BigDecimal("55000")));

        byte[] exported = dataExportService.exportFull(null);

        dataExportService.clearAll();
        assertThat(depotService.getAllPositions("EUR")).isEmpty();

        ImportSummary summary = dataExportService.importFull(exported, null);

        assertThat(summary.positions).isEqualTo(1);
        assertThat(summary.transactions).isEqualTo(1);
        assertThat(depotService.getAllPositions("EUR")).hasSize(1);
        assertThat(depotService.getAllTransactions()).hasSize(1);
        assertThat(depotService.getCurrentPrice("EUR")).isPresent();
    }

    @Test
    void exportFull_thenImportFull_encrypted_roundTripsData() {
        Position p = depotService.save(TestFixtures.position("Kraken", PositionType.EXCHANGE));
        depotService.saveTransaction(TestFixtures.buy(p, LocalDateTime.now(),
                new BigDecimal("0.02"), new BigDecimal("48000"), "EUR"));

        byte[] exported = dataExportService.exportFull("s3cr3t");
        dataExportService.clearAll();

        ImportSummary summary = dataExportService.importFull(exported, "s3cr3t");

        assertThat(summary.transactions).isEqualTo(1);
    }

    @Test
    void importFull_withWrongPassword_throws() {
        depotService.save(TestFixtures.position("Kraken", PositionType.EXCHANGE));
        byte[] exported = dataExportService.exportFull("s3cr3t");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> dataExportService.importFull(exported, "wrong-password"))
                .isInstanceOf(CsvEncryptionService.EncryptionException.class);
    }

    @Test
    void clearAll_removesAllPositionsAndTransactions() {
        Position p = depotService.save(TestFixtures.position("Bybit", PositionType.EXCHANGE));
        depotService.saveTransaction(TestFixtures.buy(p, LocalDateTime.now(),
                new BigDecimal("0.01"), new BigDecimal("50000"), "EUR"));

        dataExportService.clearAll();

        assertThat(depotService.getAllPositions("EUR")).isEmpty();
        assertThat(depotService.getAllTransactions()).isEmpty();
    }
}
