package com.thatsme4now.depot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.thatsme4now.depot.BaseIntegrationTest;
import com.thatsme4now.depot.TestFixtures;
import com.thatsme4now.depot.dto.MonthlyPriceDTO;
import com.thatsme4now.depot.entity.MonthlyPrice;
import com.thatsme4now.depot.entity.Position;
import com.thatsme4now.depot.entity.PositionType;
import com.thatsme4now.depot.repository.MonthlyPriceRepository;

class MonthlyPriceServiceTest extends BaseIntegrationTest {

    @Autowired
    private MonthlyPriceService monthlyPriceService;

    @Autowired
    private DepotService depotService;

    @Autowired
    private MonthlyPriceRepository monthlyPriceRepo;

    @Test
    void getMonthly_returnsOnlyCurrentMonth_whenNoTransactionsExist() {
        List<MonthlyPriceDTO> monthly = monthlyPriceService.getMonthly("EUR");
        assertThat(monthly).hasSize(1);
        assertThat(monthly.get(0).isCurrent()).isTrue();
        assertThat(monthly.get(0).getPrice()).isNull();
    }

    @Test
    void getMonthly_pastMonthUsesStoredPrice_currentMonthNeverHasPrice() {
        Position p = depotService.save(TestFixtures.position("Binance", PositionType.EXCHANGE));
        YearMonth twoMonthsAgo = YearMonth.now().minusMonths(2);
        depotService.saveTransaction(TestFixtures.buy(p,
                twoMonthsAgo.atDay(15).atStartOfDay(),
                new BigDecimal("0.01"), new BigDecimal("40000"), "EUR"));

        MonthlyPrice stored = new MonthlyPrice();
        stored.setTicker("BTC");
        stored.setYear(twoMonthsAgo.getYear());
        stored.setMonth(twoMonthsAgo.getMonthValue());
        stored.setCurrency("EUR");
        stored.setPrice(new BigDecimal("41000.00"));
        monthlyPriceRepo.save(stored);

        List<MonthlyPriceDTO> monthly = monthlyPriceService.getMonthly("EUR");

        MonthlyPriceDTO pastRow = monthly.stream()
                .filter(m -> m.getYear() == twoMonthsAgo.getYear() && m.getMonth() == twoMonthsAgo.getMonthValue())
                .findFirst().orElseThrow();
        assertThat(pastRow.getPrice()).isEqualByComparingTo("41000.00");

        MonthlyPriceDTO currentRow = monthly.stream().filter(MonthlyPriceDTO::isCurrent).findFirst().orElseThrow();
        assertThat(currentRow.getPrice()).isNull();
    }

    @Test
    void getPriceHistory_includesLivePrice_forCurrentMonth() {
        depotService.saveCurrentPrice(TestFixtures.currentPrice("EUR", new BigDecimal("60000")));

        List<MonthlyPriceDTO> history = monthlyPriceService.getPriceHistory("EUR");

        assertThat(history).isNotEmpty();
        MonthlyPriceDTO currentRow = history.get(history.size() - 1);
        assertThat(currentRow.isCurrent()).isTrue();
        assertThat(currentRow.getPrice()).isEqualByComparingTo("60000");
    }

    @Test
    void upsert_setsPrice_forElapsedMonth() {
        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        MonthlyPriceDTO dto = monthlyPriceService.upsert(
                lastMonth.getYear(), lastMonth.getMonthValue(), "eur", new BigDecimal("41500"));

        assertThat(dto.getCurrency()).isEqualTo("EUR");
        assertThat(dto.getPrice()).isEqualByComparingTo("41500.00");
    }

    @Test
    void upsert_rejectsCurrentOrFutureMonth() {
        YearMonth now = YearMonth.now();
        assertThatThrownBy(() -> monthlyPriceService.upsert(now.getYear(), now.getMonthValue(), "EUR", new BigDecimal("1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void upsert_rejectsInvalidMonth() {
        assertThatThrownBy(() -> monthlyPriceService.upsert(2020, 13, "EUR", new BigDecimal("1")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
