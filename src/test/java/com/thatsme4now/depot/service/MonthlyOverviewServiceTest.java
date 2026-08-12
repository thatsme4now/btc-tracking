package com.thatsme4now.depot.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.YearMonth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.thatsme4now.depot.BaseIntegrationTest;
import com.thatsme4now.depot.TestFixtures;
import com.thatsme4now.depot.dto.MonthlyBalanceDTO;
import com.thatsme4now.depot.dto.YearlyOverviewDTO;
import com.thatsme4now.depot.entity.Position;
import com.thatsme4now.depot.entity.PositionType;

class MonthlyOverviewServiceTest extends BaseIntegrationTest {

    @Autowired
    private MonthlyOverviewService monthlyOverviewService;

    @Autowired
    private DepotService depotService;

    @Test
    void getOverview_withNoTransactions_stillOffersCurrentYear() {
        YearlyOverviewDTO overview = monthlyOverviewService.getOverview(null, "EUR");
        assertThat(overview.getAvailableYears()).contains(YearMonth.now().getYear());
        assertThat(overview.getSeries()).isNotEmpty();
    }

    @Test
    void getOverview_forwardFillsBalance_acrossMonths() {
        // With year=null the range auto-starts at the first transaction's own month,
        // so there's never a "before" month to observe forward-fill from zero — use
        // an explicit, fully-elapsed year with the purchase mid-year instead, so
        // both the empty months before it and the carried-forward months after it
        // are actually part of the returned series.
        Position p = depotService.save(TestFixtures.position("Binance", PositionType.EXCHANGE));
        int lastYear = YearMonth.now().getYear() - 1;
        depotService.saveTransaction(TestFixtures.buy(p,
                java.time.LocalDateTime.of(lastYear, 6, 15, 12, 0),
                new BigDecimal("0.05000000"), new BigDecimal("40000"), "EUR"));

        YearlyOverviewDTO overview = monthlyOverviewService.getOverview(lastYear, "EUR");

        assertThat(overview.getSeries()).hasSize(12);
        MonthlyBalanceDTO may = overview.getSeries().get(4);   // month before the purchase
        MonthlyBalanceDTO june = overview.getSeries().get(5);  // month of the purchase
        MonthlyBalanceDTO december = overview.getSeries().get(11); // forward-filled afterwards

        assertThat(may.getBtcBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(june.getBtcBalance()).isEqualByComparingTo("0.05000000");
        assertThat(december.getBtcBalance()).isEqualByComparingTo("0.05000000");
    }

    @Test
    void getOverview_scopedToSingleYear_returnsFullYearRange() {
        int year = YearMonth.now().getYear() - 1;
        YearlyOverviewDTO overview = monthlyOverviewService.getOverview(year, "EUR");

        assertThat(overview.getSeries()).hasSize(12);
        assertThat(overview.getSeries()).allMatch(m -> m.getYear() == year);
    }
}
