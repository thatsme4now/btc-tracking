package com.thatsme4now.depot.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import com.thatsme4now.depot.BaseWebIntegrationTest;
import com.thatsme4now.depot.TestFixtures;
import com.thatsme4now.depot.entity.Position;
import com.thatsme4now.depot.entity.PositionType;
import com.thatsme4now.depot.service.DepotService;

class DepotViewControllerTest extends BaseWebIntegrationTest {

    @Autowired
    private DepotService depotService;

    @Test
    void root_redirectsToHoldingsPage() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/btc-tracking/holdings"));
    }

    @Test
    void flowPage_renders() throws Exception {
        mockMvc.perform(get("/btc-tracking/flow"))
                .andExpect(status().isOk())
                .andExpect(view().name("depot/flow"));
    }

    @Test
    void holdingsPage_renders() throws Exception {
        mockMvc.perform(get("/btc-tracking/holdings"))
                .andExpect(status().isOk())
                .andExpect(view().name("depot/holdings"));
    }

    @Test
    void yearlyPage_renders() throws Exception {
        mockMvc.perform(get("/btc-tracking/yearly"))
                .andExpect(status().isOk())
                .andExpect(view().name("depot/yearly"));
    }

    @Test
    void overviewPage_withPositions_populatesModel() throws Exception {
        Position p = depotService.save(TestFixtures.position("Binance", PositionType.EXCHANGE));
        depotService.saveTransaction(TestFixtures.buy(p, LocalDateTime.now(),
                new BigDecimal("0.01"), new BigDecimal("50000"), "EUR"));
        depotService.saveCurrentPrice(TestFixtures.currentPrice("EUR", new BigDecimal("55000")));

        mockMvc.perform(get("/btc-tracking"))
                .andExpect(status().isOk())
                .andExpect(view().name("depot/overview"))
                .andExpect(model().attribute("currency", "EUR"))
                .andExpect(model().attribute("noPriceAvailable", false))
                .andExpect(model().attributeExists("positions"))
                .andExpect(model().attributeExists("importHistory"));
    }

    @Test
    void overviewPage_withNoPriceSet_flagsNoPriceAvailable() throws Exception {
        depotService.save(TestFixtures.position("Binance", PositionType.EXCHANGE));

        mockMvc.perform(get("/btc-tracking"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("btcPriceDate", ""));
    }

    @Test
    void importMapping_parsesUploadedCsv() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "import.csv", "text/csv",
                "typ,date,exchange\nTrade,15.03.2023,Binance\n".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/btc-tracking/import/mapping").file(file))
                .andExpect(status().isOk())
                .andExpect(view().name("depot/import-mapping"))
                .andExpect(model().attribute("filename", "import.csv"));
    }

    @Test
    void importReviewPage_renders() throws Exception {
        mockMvc.perform(get("/btc-tracking/import/review"))
                .andExpect(status().isOk())
                .andExpect(view().name("depot/import-review"));
    }

    @Test
    void importStatusPage_renders() throws Exception {
        mockMvc.perform(get("/btc-tracking/import/status"))
                .andExpect(status().isOk())
                .andExpect(view().name("depot/import-status"));
    }
}
