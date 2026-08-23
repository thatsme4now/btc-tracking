package com.thatsme4now.depot.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.thatsme4now.depot.BaseWebIntegrationTest;
import com.thatsme4now.depot.controller.DepotRestController.MappedRow;
import com.thatsme4now.depot.dto.ImportStagingRowDTO;
import com.thatsme4now.depot.service.ImportWizardService;

class ImportRestControllerTest extends BaseWebIntegrationTest {

    @Autowired
    private ImportWizardService importWizardService;

    private static MappedRow tradeRow() {
        MappedRow row = new MappedRow();
        row.setTyp("Trade");
        row.setDate("15.03.2023 10:00:00");
        row.setExchange("Binance");
        row.setBuyQuantity("0.01");
        row.setBuyCurrency("BTC");
        row.setSellQuantity("500");
        row.setSellCurrency("EUR");
        return row;
    }

    @Test
    void stage_createsStagingRows() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("rows", List.of(tradeRow())));

        mockMvc.perform(post("/api/btc-tracking/import/stage")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staged").value(1))
                .andExpect(jsonPath("$.errorCount").value(0));
    }

    @Test
    void listStaging_returnsStagedRows() throws Exception {
        importWizardService.stageRows(List.of(tradeRow()), "EUR");

        mockMvc.perform(get("/api/btc-tracking/import/staging"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void updateStaging_appliesChange() throws Exception {
        importWizardService.stageRows(List.of(tradeRow()), "EUR");
        Long id = importWizardService.listStaging().get(0).getId();
        String body = objectMapper.writeValueAsString(Map.of("quantity", "0.02"));

        mockMvc.perform(put("/api/btc-tracking/import/staging/" + id)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(0.02));
    }

    @Test
    void updateStaging_unknownId_returns404() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("quantity", "0.02"));

        mockMvc.perform(put("/api/btc-tracking/import/staging/999999")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteStaging_removesRow() throws Exception {
        importWizardService.stageRows(List.of(tradeRow()), "EUR");
        Long id = importWizardService.listStaging().get(0).getId();

        mockMvc.perform(delete("/api/btc-tracking/import/staging/" + id))
                .andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(importWizardService.listStaging()).isEmpty();
    }

    @Test
    void bulkPair_requiresEvenIds() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("ids", List.of(1)));

        mockMvc.perform(post("/api/btc-tracking/import/staging/bulk-pair")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void bulkDelete_removesGivenRows() throws Exception {
        importWizardService.stageRows(List.of(tradeRow()), "EUR");
        Long id = importWizardService.listStaging().get(0).getId();
        String body = objectMapper.writeValueAsString(Map.of("ids", List.of(id)));

        mockMvc.perform(delete("/api/btc-tracking/import/staging/bulk")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(1));
    }

    @Test
    void cancel_clearsStaging() throws Exception {
        importWizardService.stageRows(List.of(tradeRow()), "EUR");

        mockMvc.perform(post("/api/btc-tracking/import/cancel"))
                .andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(importWizardService.listStaging()).isEmpty();
    }

    @Test
    void confirm_commitsStagedRows() throws Exception {
        importWizardService.stageRows(List.of(tradeRow()), "EUR");
        String body = objectMapper.writeValueAsString(Map.of("filename", "test.csv", "totalRows", 1));

        mockMvc.perform(post("/api/btc-tracking/import/confirm")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedRows").value(1));
    }

    @Test
    void history_listsCompletedImports() throws Exception {
        importWizardService.stageRows(List.of(tradeRow()), "EUR");
        importWizardService.confirmImport("test.csv", 1);

        mockMvc.perform(get("/api/btc-tracking/import/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void deleteHistory_unknownId_returns404() throws Exception {
        mockMvc.perform(delete("/api/btc-tracking/import/history/999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteHistory_existingEntry_succeeds() throws Exception {
        importWizardService.stageRows(List.of(tradeRow()), "EUR");
        var result = importWizardService.confirmImport("test.csv", 1);

        mockMvc.perform(delete("/api/btc-tracking/import/history/" + result.historyId)
                        .param("deleteTransactions", "true"))
                .andExpect(status().isNoContent());
    }
}
