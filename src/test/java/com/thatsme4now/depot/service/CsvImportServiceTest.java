package com.thatsme4now.depot.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.thatsme4now.depot.BaseIntegrationTest;
import com.thatsme4now.depot.controller.DepotRestController.MappedRow;
import com.thatsme4now.depot.dto.TransactionDTO;
import com.thatsme4now.depot.entity.Position;
import com.thatsme4now.depot.entity.PositionType;
import com.thatsme4now.depot.entity.TransactionType;

class CsvImportServiceTest extends BaseIntegrationTest {

    @Autowired
    private CsvImportService csvImportService;

    @Autowired
    private DepotService depotService;

    @Test
    void getLocalDateTimeByString_parsesGermanFormat() {
        LocalDateTime dt = csvImportService.getLocalDateTimeByString("15.03.2023 14:30:00");
        assertThat(dt).isEqualTo(LocalDateTime.of(2023, 3, 15, 14, 30, 0));
    }

    @Test
    void getLocalDateTimeByString_parsesUsFormat() {
        LocalDateTime dt = csvImportService.getLocalDateTimeByString("03/15/2023 14:30:00");
        assertThat(dt).isEqualTo(LocalDateTime.of(2023, 3, 15, 14, 30, 0));
    }

    @Test
    void getLocalDateTimeByString_returnsNull_forUnparsableInput() {
        assertThat(csvImportService.getLocalDateTimeByString("not-a-date")).isNull();
    }

    @Test
    void resolvePosition_createsWalletType_whenLabelContainsWallet() {
        Position p = csvImportService.resolvePosition("My Hardware Wallet");
        assertThat(p.getType()).isEqualTo(PositionType.WALLET);
    }

    @Test
    void resolvePosition_createsExchangeType_byDefault() {
        Position p = csvImportService.resolvePosition("Binance");
        assertThat(p.getType()).isEqualTo(PositionType.EXCHANGE);
    }

    @Test
    void resolvePosition_reusesExistingPosition_withSameLabel() {
        Position first = csvImportService.resolvePosition("Binance");
        Position second = csvImportService.resolvePosition("Binance");
        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    void importMapped_returnsNull_forEmptyInput() {
        assertThat(csvImportService.importMapped(List.of())).isNull();
        assertThat(csvImportService.importMapped(null)).isNull();
    }

    @Test
    void importMapped_tradeRow_createsBuyTransaction() {
        MappedRow row = new MappedRow();
        row.setTyp("Trade");
        row.setDate("15.03.2023 14:30:00");
        row.setExchange("Binance");
        row.setBuyQuantity("0.01");
        row.setBuyCurrency("BTC");
        row.setSellQuantity("500");
        row.setSellCurrency("EUR");

        CsvImportService.ImportResult result = csvImportService.importMapped(List.of(row));

        assertThat(result.inserted).isEqualTo(1);
        List<TransactionDTO> all = depotService.getAllTransactions();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getType()).isEqualTo(TransactionType.BUY);
        assertThat(all.get(0).getQuantity()).isEqualByComparingTo("0.01");
    }

    @Test
    void importMapped_seedsCurrentPrice_whenNoneSetYet() {
        MappedRow row = new MappedRow();
        row.setTyp("Trade");
        row.setDate("15.03.2023 14:30:00");
        row.setExchange("Binance");
        row.setBuyQuantity("0.01");
        row.setBuyCurrency("BTC");
        row.setSellQuantity("500");
        row.setSellCurrency("EUR");

        csvImportService.importMapped(List.of(row));

        assertThat(depotService.getCurrentPrice("EUR")).isPresent();
    }

    @Test
    void importMapped_rowWithExistingTransactionId_isIgnored() {
        MappedRow row1 = new MappedRow();
        row1.setTyp("Trade");
        row1.setDate("15.03.2023 14:30:00");
        row1.setExchange("Binance");
        row1.setBuyQuantity("0.01");
        row1.setBuyCurrency("BTC");
        row1.setSellQuantity("500");
        row1.setSellCurrency("EUR");
        row1.setTransactionId("fixed-tx-id");

        csvImportService.importMapped(List.of(row1));

        MappedRow row2 = new MappedRow();
        row2.setTyp("Trade");
        row2.setDate("16.03.2023 14:30:00");
        row2.setExchange("Binance");
        row2.setBuyQuantity("0.02");
        row2.setBuyCurrency("BTC");
        row2.setSellQuantity("1000");
        row2.setSellCurrency("EUR");
        row2.setTransactionId("fixed-tx-id");

        CsvImportService.ImportResult result = csvImportService.importMapped(List.of(row2));

        assertThat(result.ignoredByTransactionId).isEqualTo(1);
        assertThat(depotService.getAllTransactions()).hasSize(1);
    }

    @Test
    void importMapped_selfRow_createsPairedTransfer() {
        MappedRow row = new MappedRow();
        row.setTyp("Selbst");
        row.setDate("15.03.2023 14:30:00");
        row.setExchange("Ledger Wallet");
        row.setBuyQuantity("0.01");
        row.setFee("0.0001");

        csvImportService.importMapped(List.of(row));

        List<TransactionDTO> all = depotService.getAllTransactions();
        assertThat(all).hasSize(2);
        assertThat(all).extracting(TransactionDTO::getType)
                .containsExactlyInAnyOrder(TransactionType.TRANSFER_OUT, TransactionType.TRANSFER_IN);
        assertThat(all.get(0).getTransferId()).isEqualTo(all.get(1).getTransferId());
    }
}
