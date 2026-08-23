package com.thatsme4now.depot.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.thatsme4now.depot.BaseIntegrationTest;
import com.thatsme4now.depot.TestFixtures;
import com.thatsme4now.depot.dto.PortfolioMetricsDTO;
import com.thatsme4now.depot.dto.YearlyHoldingsDTO;
import com.thatsme4now.depot.entity.Position;
import com.thatsme4now.depot.entity.PositionType;

class HoldingsYearlyServiceTest extends BaseIntegrationTest {

    @Autowired
    private HoldingsYearlyService holdingsYearlyService;

    @Autowired
    private DepotService depotService;

    @Test
    void getYearlyHoldings_buyOnly_accumulatesBalanceAndCostBasis() {
        Position p = depotService.save(TestFixtures.position("Binance", PositionType.EXCHANGE));
        int year = Year.now().getValue();
        depotService.saveTransaction(TestFixtures.buy(p, LocalDateTime.of(year, 2, 1, 12, 0),
                new BigDecimal("0.01"), new BigDecimal("50000"), "EUR"));
        depotService.saveCurrentPrice(TestFixtures.currentPrice("EUR", new BigDecimal("60000")));

        List<YearlyHoldingsDTO> yearly = holdingsYearlyService.getYearlyHoldings("EUR");

        YearlyHoldingsDTO currentYear = yearly.stream().filter(YearlyHoldingsDTO::isCurrentYear).findFirst().orElseThrow();
        assertThat(currentYear.getTotalBuys()).isEqualByComparingTo("500.00");
        assertThat(currentYear.getBtcBalance()).isEqualByComparingTo("0.01000000");
        assertThat(currentYear.getBuysByExchange()).containsKey("Binance");
        // unrealized = 0.01 * 60000 - 500 = 100
        assertThat(currentYear.getUnrealizedPnl()).isEqualByComparingTo("100.00");
    }

    @Test
    void getYearlyHoldings_buyThenSell_computesRealizedPnlFromAverageCost() {
        Position p = depotService.save(TestFixtures.position("Binance", PositionType.EXCHANGE));
        int year = Year.now().getValue();
        depotService.saveTransaction(TestFixtures.buy(p, LocalDateTime.of(year, 1, 1, 12, 0),
                new BigDecimal("0.02"), new BigDecimal("50000"), "EUR"));
        depotService.saveTransaction(TestFixtures.sell(p, LocalDateTime.of(year, 2, 1, 12, 0),
                new BigDecimal("0.01"), new BigDecimal("55000"), "EUR"));

        List<YearlyHoldingsDTO> yearly = holdingsYearlyService.getYearlyHoldings("EUR");
        YearlyHoldingsDTO currentYear = yearly.stream().filter(YearlyHoldingsDTO::isCurrentYear).findFirst().orElseThrow();

        // avg cost = 1000/0.02 = 50000/BTC, cost of 0.01 sold = 500, proceeds = 550 -> realized = 50
        assertThat(currentYear.getTotalSells()).isEqualByComparingTo("550.00");
        assertThat(currentYear.getRealizedPnl()).isEqualByComparingTo("50.00");
        assertThat(currentYear.getBtcBalance()).isEqualByComparingTo("0.01000000");
    }

    @Test
    void getYearlyHoldings_withNoTransactions_stillReturnsCurrentYearRow() {
        List<YearlyHoldingsDTO> yearly = holdingsYearlyService.getYearlyHoldings("EUR");
        assertThat(yearly).hasSize(1);
        assertThat(yearly.get(0).isCurrentYear()).isTrue();
        assertThat(yearly.get(0).getBtcBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void computePortfolioMetrics_aggregatesAcrossPositions() {
        Position p = depotService.save(TestFixtures.position("Binance", PositionType.EXCHANGE));
        depotService.saveTransaction(TestFixtures.buy(p, LocalDateTime.now().minusDays(10),
                new BigDecimal("0.01"), new BigDecimal("50000"), "EUR"));
        depotService.saveCurrentPrice(TestFixtures.currentPrice("EUR", new BigDecimal("60000")));

        PortfolioMetricsDTO metrics = holdingsYearlyService.computePortfolioMetrics("EUR");

        assertThat(metrics.getTotalBtc()).isEqualByComparingTo("0.01000000");
        assertThat(metrics.getTotalValue()).isEqualByComparingTo("600.00");
        assertThat(metrics.getInvested()).isEqualByComparingTo("500.00");
        assertThat(metrics.getGainLoss()).isEqualByComparingTo("100.00");
        assertThat(metrics.getTransactionCount()).isEqualTo(1);
        assertThat(metrics.getPerformancePct()).isEqualByComparingTo("20.00");
    }

    @Test
    void computePortfolioMetrics_withNoData_returnsZeroes() {
        PortfolioMetricsDTO metrics = holdingsYearlyService.computePortfolioMetrics("EUR");
        assertThat(metrics.getTotalBtc()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(metrics.getPerformancePct()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
