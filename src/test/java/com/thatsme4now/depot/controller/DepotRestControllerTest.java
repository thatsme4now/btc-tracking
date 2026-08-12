package com.thatsme4now.depot.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import com.thatsme4now.depot.BaseWebIntegrationTest;
import com.thatsme4now.depot.TestFixtures;
import com.thatsme4now.depot.dto.TransactionDTO;
import com.thatsme4now.depot.entity.Position;
import com.thatsme4now.depot.entity.PositionType;
import com.thatsme4now.depot.entity.Transaction;
import com.thatsme4now.depot.service.DepotService;

class DepotRestControllerTest extends BaseWebIntegrationTest {

    @Autowired
    private DepotService depotService;

    // ── Positions ────────────────────────────────────────────────────────

    @Test
    void createPosition_persistsAndReturnsId() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("label", "Binance", "type", "EXCHANGE"));

        mockMvc.perform(post("/api/btc-tracking/positions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Binance"));

        mockMvc.perform(get("/api/btc-tracking/positions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void getPosition_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/btc-tracking/positions/999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletePosition_withTransactions_isRejected() throws Exception {
        Position p = depotService.save(TestFixtures.position("Binance", PositionType.EXCHANGE));
        depotService.saveTransaction(TestFixtures.buy(p, LocalDateTime.now(),
                new BigDecimal("0.01"), new BigDecimal("50000"), "EUR"));

        mockMvc.perform(delete("/api/btc-tracking/positions/" + p.getId()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletePosition_withoutTransactions_succeeds() throws Exception {
        Position p = depotService.save(TestFixtures.position("Binance", PositionType.EXCHANGE));

        mockMvc.perform(delete("/api/btc-tracking/positions/" + p.getId()))
                .andExpect(status().isOk());
        assertThatPositionIsGone(p.getId());
    }

    private void assertThatPositionIsGone(Long id) {
        org.assertj.core.api.Assertions.assertThat(depotService.getPosition(id)).isEmpty();
    }

    // ── Transactions ─────────────────────────────────────────────────────

    @Test
    void addTransaction_createsBuy() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "date", "15.03.2023 10:00:00",
                "type", "BUY",
                "quantity", "0.01",
                "quantityFiat", "500",
                "currency", "EUR",
                "exchange", "Binance"
        ));

        mockMvc.perform(post("/api/btc-tracking/transactions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("BUY"));

        org.assertj.core.api.Assertions.assertThat(depotService.getAllTransactions()).hasSize(1);
    }

    @Test
    void addTransaction_transferOutWithTarget_createsPairedTransferIn() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "date", "15.03.2023 10:00:00",
                "type", "TRANSFER_OUT",
                "quantity", "0.005",
                "currency", "EUR",
                "exchange", "Binance",
                "transferTarget", "Ledger"
        ));

        mockMvc.perform(post("/api/btc-tracking/transactions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        List<TransactionDTO> all = depotService.getAllTransactions();
        org.assertj.core.api.Assertions.assertThat(all).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(all.get(0).getTransferId()).isEqualTo(all.get(1).getTransferId());
    }

    @Test
    void updateTransaction_unknownId_returns404() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("quantity", "0.02"));

        mockMvc.perform(put("/api/btc-tracking/transactions/999999")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteTransaction_removesIt() throws Exception {
        Position p = depotService.save(TestFixtures.position("Binance", PositionType.EXCHANGE));
        Transaction tx = depotService.saveTransaction(TestFixtures.buy(p, LocalDateTime.now(),
                new BigDecimal("0.01"), new BigDecimal("50000"), "EUR"));

        mockMvc.perform(delete("/api/btc-tracking/transactions/" + tx.getId()))
                .andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(depotService.getTransaction(tx.getId())).isEmpty();
    }

    @Test
    void bulkDelete_removesMultipleTransactions() throws Exception {
        Position p = depotService.save(TestFixtures.position("Binance", PositionType.EXCHANGE));
        Transaction tx1 = depotService.saveTransaction(TestFixtures.buy(p, LocalDateTime.now(),
                new BigDecimal("0.01"), new BigDecimal("50000"), "EUR"));
        Transaction tx2 = depotService.saveTransaction(TestFixtures.buy(p, LocalDateTime.now(),
                new BigDecimal("0.02"), new BigDecimal("50000"), "EUR"));

        String body = objectMapper.writeValueAsString(List.of(tx1.getId(), tx2.getId()));

        mockMvc.perform(delete("/api/btc-tracking/transactions/bulk")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(2));

        org.assertj.core.api.Assertions.assertThat(depotService.getAllTransactions()).isEmpty();
    }

    @Test
    void bulkPair_requiresEvenNumberOfIds() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("ids", List.of(1, 2, 3)));

        mockMvc.perform(post("/api/btc-tracking/transactions/bulk-pair")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void bulkSoloTransfer_rejectsNonTransferTypes() throws Exception {
        Position p = depotService.save(TestFixtures.position("Binance", PositionType.EXCHANGE));
        Transaction tx = depotService.saveTransaction(TestFixtures.buy(p, LocalDateTime.now(),
                new BigDecimal("0.01"), new BigDecimal("50000"), "EUR"));

        String body = objectMapper.writeValueAsString(Map.of("ids", List.of(tx.getId())));

        mockMvc.perform(post("/api/btc-tracking/transactions/bulk-solo-transfer")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    // ── Current price / settings / metrics ──────────────────────────────

    @Test
    void currentPrice_setThenGet() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("price", "55000", "currency", "EUR"));

        mockMvc.perform(put("/api/btc-tracking/current-price")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/btc-tracking/current-price").param("currency", "EUR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(55000));
    }

    @Test
    void currentPrice_getWithoutData_returnsZero() throws Exception {
        mockMvc.perform(get("/api/btc-tracking/current-price").param("currency", "EUR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(0));
    }

    @Test
    void setCurrentPrice_rejectsNonPositivePrice() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("price", "0", "currency", "EUR"));

        mockMvc.perform(put("/api/btc-tracking/current-price")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void settings_updateThenGet() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("taxHoldingPeriodCutoffDate", "2024-01-01"));

        mockMvc.perform(put("/api/btc-tracking/settings")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taxHoldingPeriodCutoffDate").value("2024-01-01"));

        mockMvc.perform(get("/api/btc-tracking/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taxHoldingPeriodCutoffDate").value("2024-01-01"));
    }

    @Test
    void metrics_withPositions_returnsAggregatedValues() throws Exception {
        Position p = depotService.save(TestFixtures.position("Binance", PositionType.EXCHANGE));
        depotService.saveTransaction(TestFixtures.buy(p, LocalDateTime.now(),
                new BigDecimal("0.01"), new BigDecimal("50000"), "EUR"));
        depotService.saveCurrentPrice(TestFixtures.currentPrice("EUR", new BigDecimal("60000")));

        mockMvc.perform(get("/api/btc-tracking/metrics").param("currency", "EUR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalValue").value(600.0))
                .andExpect(jsonPath("$.invested").value(500.0));
    }

    // ── Historical / monthly prices, yearly overview, flow, history ────

    @Test
    void historicalPrices_upsertThenList() throws Exception {
        int lastYear = java.time.Year.now().getValue() - 1;
        String body = objectMapper.writeValueAsString(Map.of("year", lastYear, "currency", "EUR", "price", "42000"));

        mockMvc.perform(put("/api/btc-tracking/historical-prices")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/btc-tracking/historical-prices").param("currency", "EUR"))
                .andExpect(status().isOk());
    }

    @Test
    void monthlyPrices_upsertThenList() throws Exception {
        java.time.YearMonth lastMonth = java.time.YearMonth.now().minusMonths(1);
        String body = objectMapper.writeValueAsString(Map.of(
                "year", lastMonth.getYear(), "month", lastMonth.getMonthValue(),
                "currency", "EUR", "price", "41000"));

        mockMvc.perform(put("/api/btc-tracking/monthly-prices")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/btc-tracking/monthly-prices").param("currency", "EUR"))
                .andExpect(status().isOk());
    }

    @Test
    void yearlyOverview_returnsSeries() throws Exception {
        mockMvc.perform(get("/api/btc-tracking/yearly-overview").param("currency", "EUR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.series").isArray());
    }

    @Test
    void flow_withNoData_returnsEmptyGraph() throws Exception {
        mockMvc.perform(get("/api/btc-tracking/flow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes", hasSize(0)))
                .andExpect(jsonPath("$.links", hasSize(0)));
    }

    @Test
    void history_returnsEmptyList_byDefault() throws Exception {
        mockMvc.perform(get("/api/btc-tracking/history"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    // ── Export / full backup ────────────────────────────────────────────

    @Test
    void exportCsv_returnsAttachment() throws Exception {
        Position p = depotService.save(TestFixtures.position("Binance", PositionType.EXCHANGE));
        depotService.saveTransaction(TestFixtures.buy(p, LocalDateTime.now(),
                new BigDecimal("0.01"), new BigDecimal("50000"), "EUR"));

        mockMvc.perform(post("/api/btc-tracking/export").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"));
    }

    @Test
    void exportFullThenImportFull_roundTripsViaRestEndpoints() throws Exception {
        Position p = depotService.save(TestFixtures.position("Binance", PositionType.EXCHANGE));
        depotService.saveTransaction(TestFixtures.buy(p, LocalDateTime.now(),
                new BigDecimal("0.01"), new BigDecimal("50000"), "EUR"));

        byte[] exported = mockMvc.perform(post("/api/btc-tracking/export-full").content("{}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        depotService.deleteTransaction();
        depotService.delete(p.getId());

        MockMultipartFile file = new MockMultipartFile("file", "backup.json",
                MediaType.APPLICATION_JSON_VALUE, exported);

        mockMvc.perform(multipart("/api/btc-tracking/import-full").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions").value(1));
    }

    @Test
    void importMapped_createsTransaction() throws Exception {
        String body = "{\"rows\":[{\"typ\":\"Trade\",\"date\":\"15.03.2023 10:00:00\",\"exchange\":\"Binance\","
                + "\"buyQuantity\":\"0.01\",\"buyCurrency\":\"BTC\",\"sellQuantity\":\"500\",\"sellCurrency\":\"EUR\"}]}";

        mockMvc.perform(post("/api/btc-tracking/import-mapped")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(1));
    }

    @Test
    void deleteAll_clearsEverything() throws Exception {
        Position p = depotService.save(TestFixtures.position("Binance", PositionType.EXCHANGE));
        depotService.saveTransaction(TestFixtures.buy(p, LocalDateTime.now(),
                new BigDecimal("0.01"), new BigDecimal("50000"), "EUR"));

        mockMvc.perform(delete("/api/btc-tracking/"))
                .andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(depotService.getAllTransactions()).isEmpty();
    }
}
