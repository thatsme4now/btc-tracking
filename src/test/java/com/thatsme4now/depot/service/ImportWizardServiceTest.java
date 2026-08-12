package com.thatsme4now.depot.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import com.thatsme4now.depot.BaseIntegrationTest;
import com.thatsme4now.depot.controller.DepotRestController.MappedRow;
import com.thatsme4now.depot.dto.ImportHistoryDTO;
import com.thatsme4now.depot.dto.ImportStagingRowDTO;
import com.thatsme4now.depot.entity.TransactionType;
import com.thatsme4now.depot.service.ImportWizardService.ConfirmResult;
import com.thatsme4now.depot.service.ImportWizardService.StageResult;
import com.thatsme4now.depot.service.ImportWizardService.StagingUpdateRequest;
import com.thatsme4now.depot.service.ImportWizardService.UploadResult;

class ImportWizardServiceTest extends BaseIntegrationTest {

    @Autowired
    private ImportWizardService importWizardService;

    @Autowired
    private DepotService depotService;

    private static MappedRow tradeRow(String date, String exchange, String buyQty, String buyCur,
                                       String sellQty, String sellCur) {
        MappedRow row = new MappedRow();
        row.setTyp("Trade");
        row.setDate(date);
        row.setExchange(exchange);
        row.setBuyQuantity(buyQty);
        row.setBuyCurrency(buyCur);
        row.setSellQuantity(sellQty);
        row.setSellCurrency(sellCur);
        return row;
    }

    @Test
    void parseUpload_detectsCommaDelimiter_andParsesRows() throws Exception {
        String csv = "typ,date,exchange\nTrade,15.03.2023 14:30:00,Binance\n";
        MockMultipartFile file = new MockMultipartFile("file", "import.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        UploadResult result = importWizardService.parseUpload(file);

        assertThat(result.headers).containsExactly("typ", "date", "exchange");
        assertThat(result.rows).hasSize(1);
        assertThat(result.rows.get(0).get("exchange")).isEqualTo("Binance");
    }

    @Test
    void parseUpload_detectsSemicolonDelimiter() throws Exception {
        String csv = "typ;date;exchange\nTrade;15.03.2023 14:30:00;Kraken\n";
        MockMultipartFile file = new MockMultipartFile("file", "import.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        UploadResult result = importWizardService.parseUpload(file);

        assertThat(result.headers).containsExactly("typ", "date", "exchange");
        assertThat(result.rows.get(0).get("exchange")).isEqualTo("Kraken");
    }

    @Test
    void stageRows_validTradeRow_isStagedWithoutError() {
        StageResult result = importWizardService.stageRows(
                List.of(tradeRow("15.03.2023 14:30:00", "Binance", "0.01", "BTC", "500", "EUR")), "EUR");

        assertThat(result.staged).isEqualTo(1);
        assertThat(result.errorCount).isZero();
        assertThat(importWizardService.listStaging()).hasSize(1);
        assertThat(importWizardService.listStaging().get(0).getType()).isEqualTo(TransactionType.BUY);
    }

    @Test
    void stageRows_missingExchange_isFlaggedAsError() {
        MappedRow row = tradeRow("15.03.2023 14:30:00", null, "0.01", "BTC", "500", "EUR");

        StageResult result = importWizardService.stageRows(List.of(row), "EUR");

        assertThat(result.errorCount).isEqualTo(1);
        assertThat(importWizardService.listStaging().get(0).isHasError()).isTrue();
    }

    @Test
    void stageRows_nonBtcTrade_isSkippedAndCounted() {
        MappedRow row = tradeRow("15.03.2023 14:30:00", "Binance", "100", "USDT", "500", "EUR");

        StageResult result = importWizardService.stageRows(List.of(row), "EUR");

        assertThat(result.skippedNoBtc).isEqualTo(1);
        assertThat(importWizardService.listStaging()).isEmpty();
    }

    @Test
    void stageRows_fxWarning_setWhenCurrencyDiffersWithoutExchangeRate() {
        MappedRow row = tradeRow("15.03.2023 14:30:00", "Binance", "0.01", "BTC", "550", "USD");

        importWizardService.stageRows(List.of(row), "EUR");

        assertThat(importWizardService.listStaging().get(0).isFxWarning()).isTrue();
    }

    @Test
    void updateStaging_appliesFieldsAndRecomputesErrors() {
        importWizardService.stageRows(
                List.of(tradeRow("15.03.2023 14:30:00", "Binance", "0.01", "BTC", "500", "EUR")), "EUR");
        Long id = importWizardService.listStaging().get(0).getId();

        StagingUpdateRequest update = new StagingUpdateRequest();
        update.setQuantity(new BigDecimal("0.02"));

        ImportStagingRowDTO updated = importWizardService.updateStaging(id, update, "EUR");

        assertThat(updated.getQuantity()).isEqualByComparingTo("0.02");
    }

    @Test
    void deleteStaging_removesRow() {
        importWizardService.stageRows(
                List.of(tradeRow("15.03.2023 14:30:00", "Binance", "0.01", "BTC", "500", "EUR")), "EUR");
        Long id = importWizardService.listStaging().get(0).getId();

        importWizardService.deleteStaging(id);

        assertThat(importWizardService.listStaging()).isEmpty();
    }

    @Test
    void bulkDeleteStaging_removesMultipleRows() {
        importWizardService.stageRows(List.of(
                tradeRow("15.03.2023 14:30:00", "Binance", "0.01", "BTC", "500", "EUR"),
                tradeRow("16.03.2023 14:30:00", "Binance", "0.02", "BTC", "1000", "EUR")
        ), "EUR");
        List<Long> ids = importWizardService.listStaging().stream().map(ImportStagingRowDTO::getId).toList();

        int deleted = importWizardService.bulkDeleteStaging(ids);

        assertThat(deleted).isEqualTo(2);
        assertThat(importWizardService.listStaging()).isEmpty();
    }

    @Test
    void bulkMoveStaging_updatesPositionLabelAndClearsError() {
        MappedRow row = tradeRow("15.03.2023 14:30:00", null, "0.01", "BTC", "500", "EUR");
        importWizardService.stageRows(List.of(row), "EUR");
        Long id = importWizardService.listStaging().get(0).getId();
        assertThat(importWizardService.listStaging().get(0).isHasError()).isTrue();

        importWizardService.bulkMoveStaging(List.of(id), "Kraken");

        ImportStagingRowDTO updated = importWizardService.listStaging().get(0);
        assertThat(updated.getPositionLabel()).isEqualTo("Kraken");
        assertThat(updated.isHasError()).isFalse();
    }

    @Test
    void cancelImport_clearsStagingTable() {
        importWizardService.stageRows(
                List.of(tradeRow("15.03.2023 14:30:00", "Binance", "0.01", "BTC", "500", "EUR")), "EUR");

        importWizardService.cancelImport();

        assertThat(importWizardService.listStaging()).isEmpty();
    }

    @Test
    void confirmImport_commitsStagedRowsAsTransactions_andRecordsHistory() {
        importWizardService.stageRows(
                List.of(tradeRow("15.03.2023 14:30:00", "Binance", "0.01", "BTC", "500", "EUR")), "EUR");

        ConfirmResult result = importWizardService.confirmImport("my-import.csv", 1);

        assertThat(result.importedRows).isEqualTo(1);
        assertThat(result.errorRows).isZero();
        assertThat(depotService.getAllTransactions()).hasSize(1);
        assertThat(importWizardService.listStaging()).isEmpty();

        List<ImportHistoryDTO> history = importWizardService.getHistory(10);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getFilename()).isEqualTo("my-import.csv");
        assertThat(history.get(0).getLinkedTransactionCount()).isEqualTo(1);
    }

    @Test
    void confirmImport_errorRow_isNotImportedAndReported() {
        MappedRow row = tradeRow("15.03.2023 14:30:00", null, "0.01", "BTC", "500", "EUR");
        importWizardService.stageRows(List.of(row), "EUR");

        ConfirmResult result = importWizardService.confirmImport("bad-import.csv", 1);

        assertThat(result.importedRows).isZero();
        assertThat(result.errorRows).isEqualTo(1);
        assertThat(result.errors).hasSize(1);
        assertThat(depotService.getAllTransactions()).isEmpty();
    }

    @Test
    void deleteHistory_withoutDeletingTransactions_unlinksButKeepsTransactions() {
        importWizardService.stageRows(
                List.of(tradeRow("15.03.2023 14:30:00", "Binance", "0.01", "BTC", "500", "EUR")), "EUR");
        ConfirmResult result = importWizardService.confirmImport("keep.csv", 1);

        importWizardService.deleteHistory(result.historyId, false);

        assertThat(importWizardService.getHistory(10)).isEmpty();
        assertThat(depotService.getAllTransactions()).hasSize(1);
    }

    @Test
    void deleteHistory_withDeletingTransactions_removesThem() {
        importWizardService.stageRows(
                List.of(tradeRow("15.03.2023 14:30:00", "Binance", "0.01", "BTC", "500", "EUR")), "EUR");
        ConfirmResult result = importWizardService.confirmImport("remove.csv", 1);

        importWizardService.deleteHistory(result.historyId, true);

        assertThat(depotService.getAllTransactions()).isEmpty();
    }

    @Test
    void deleteHistory_unknownId_throws() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> importWizardService.deleteHistory(999999L, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
