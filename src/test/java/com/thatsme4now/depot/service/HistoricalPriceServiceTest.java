package com.thatsme4now.depot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.thatsme4now.depot.BaseIntegrationTest;
import com.thatsme4now.depot.TestFixtures;
import com.thatsme4now.depot.dto.HistoricalPriceDTO;
import com.thatsme4now.depot.entity.Position;
import com.thatsme4now.depot.entity.PositionType;

class HistoricalPriceServiceTest extends BaseIntegrationTest {

    @Autowired
    private HistoricalPriceService historicalPriceService;

    @Autowired
    private DepotService depotService;

    @Test
    void getYearly_returnsEmptyList_whenNoTransactionsExist() {
        assertThat(historicalPriceService.getYearly("EUR")).isEmpty();
    }

    @Test
    void getYearly_returnsOneRowPerPastYear_withNullPriceUntilSet() {
        Position p = depotService.save(TestFixtures.position("Binance", PositionType.EXCHANGE));
        int firstYear = Year.now().getValue() - 2;
        depotService.saveTransaction(TestFixtures.buy(p,
                LocalDateTime.of(firstYear, 3, 1, 12, 0),
                new BigDecimal("0.01"), new BigDecimal("40000"), "EUR"));

        List<HistoricalPriceDTO> yearly = historicalPriceService.getYearly("EUR");

        // years from firstYear up to (not including) the current year
        assertThat(yearly).extracting(HistoricalPriceDTO::getYear)
                .containsExactly(firstYear, firstYear + 1);
        assertThat(yearly).allMatch(dto -> dto.getPrice() == null);
    }

    @Test
    void upsert_setsPrice_forElapsedYear() {
        int lastYear = Year.now().getValue() - 1;
        HistoricalPriceDTO dto = historicalPriceService.upsert(lastYear, "eur", new BigDecimal("42000.005"));

        assertThat(dto.getYear()).isEqualTo(lastYear);
        assertThat(dto.getCurrency()).isEqualTo("EUR");
        assertThat(dto.getPrice()).isEqualByComparingTo("42000.01");
    }

    @Test
    void upsert_rejectsCurrentOrFutureYear() {
        int currentYear = Year.now().getValue();
        assertThatThrownBy(() -> historicalPriceService.upsert(currentYear, "EUR", new BigDecimal("1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void upsert_rejectsInvalidInput() {
        assertThatThrownBy(() -> historicalPriceService.upsert(null, "EUR", new BigDecimal("1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> historicalPriceService.upsert(2020, "", new BigDecimal("1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> historicalPriceService.upsert(2020, "EUR", BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
