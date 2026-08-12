package com.thatsme4now.depot.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.thatsme4now.depot.BaseIntegrationTest;
import com.thatsme4now.depot.TestFixtures;
import com.thatsme4now.depot.dto.PositionDTO;
import com.thatsme4now.depot.dto.TransactionDTO;
import com.thatsme4now.depot.entity.AppSettings;
import com.thatsme4now.depot.entity.CurrentPrice;
import com.thatsme4now.depot.entity.Position;
import com.thatsme4now.depot.entity.PositionType;
import com.thatsme4now.depot.entity.Transaction;
import com.thatsme4now.depot.entity.TransactionType;

class DepotServiceTest extends BaseIntegrationTest {

    @Autowired
    private DepotService depotService;

    @Test
    void getAllPositions_returnsEmptyList_whenNoPositionsExist() {
        assertThat(depotService.getAllPositions("EUR")).isEmpty();
    }

    @Test
    void getAllPositions_computesQuantityInvestedAndGainLoss() {
        Position binance = depotService.save(TestFixtures.position("Binance", PositionType.EXCHANGE));
        depotService.saveCurrentPrice(TestFixtures.currentPrice("EUR", new BigDecimal("60000")));

        depotService.saveTransaction(TestFixtures.buy(binance, LocalDateTime.now().minusDays(10),
                new BigDecimal("0.01000000"), new BigDecimal("50000.00"), "EUR"));

        List<PositionDTO> positions = depotService.getAllPositions("EUR");

        assertThat(positions).hasSize(1);
        PositionDTO dto = positions.get(0);
        assertThat(dto.getQuantity()).isEqualByComparingTo("0.01000000");
        assertThat(dto.getInvested()).isEqualByComparingTo("500.00");
        assertThat(dto.getAvgPurchasePrice()).isEqualByComparingTo("50000.00");
        // value = 0.01 * 60000 = 600, gainLoss = 600 - 500 = 100
        assertThat(dto.getTotalValue()).isEqualByComparingTo("600.00");
        assertThat(dto.getGainLoss()).isEqualByComparingTo("100.00");
    }

    @Test
    void getAllPositions_withoutCurrentPrice_setsZeroCurrentPrice() {
        Position wallet = depotService.save(TestFixtures.position("Bitbox", PositionType.WALLET));
        depotService.saveTransaction(TestFixtures.buy(wallet, LocalDateTime.now(),
                new BigDecimal("0.01"), new BigDecimal("50000"), "EUR"));

        PositionDTO dto = depotService.getAllPositions("EUR").get(0);
        assertThat(dto.getCurrentPrice()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.getTotalValue()).isNull();
    }

    @Test
    void positionCrud_saveGetDelete() {
        Position p = depotService.save(TestFixtures.position("Ledger", PositionType.WALLET));
        assertThat(p.getId()).isNotNull();

        assertThat(depotService.getPosition(p.getId())).isPresent();

        depotService.delete(p.getId());
        assertThat(depotService.getPosition(p.getId())).isEmpty();
    }

    @Test
    void currentPrice_saveAndRetrieve() {
        depotService.saveCurrentPrice(TestFixtures.currentPrice("USD", new BigDecimal("65000")));
        assertThat(depotService.getCurrentPrice("USD")).isPresent()
                .get().extracting(CurrentPrice::getPrice).isEqualTo(new BigDecimal("65000"));
        assertThat(depotService.getCurrentPrice("GBP")).isEmpty();
    }

    @Test
    void getAppSettings_createsDefaultRow_onFirstAccess() {
        AppSettings settings = depotService.getAppSettings();
        assertThat(settings.getId()).isEqualTo(1L);
        assertThat(settings.getTaxHoldingPeriodCutoffDate()).isNull();

        // second call returns the same persisted row, not a new one
        AppSettings again = depotService.getAppSettings();
        assertThat(again.getId()).isEqualTo(settings.getId());
    }

    @Test
    void transactions_crudAndOrdering() {
        Position p = depotService.save(TestFixtures.position("Kraken", PositionType.EXCHANGE));
        Transaction older = TestFixtures.buy(p, LocalDateTime.now().minusDays(5),
                new BigDecimal("0.01"), new BigDecimal("50000"), "EUR");
        Transaction newer = TestFixtures.buy(p, LocalDateTime.now().minusDays(1),
                new BigDecimal("0.02"), new BigDecimal("51000"), "EUR");
        depotService.saveTransaction(older);
        depotService.saveTransaction(newer);

        assertThat(depotService.getTransactionCount()).isEqualTo(2);
        assertThat(depotService.getTransactionCount(p.getId())).isEqualTo(2);

        List<TransactionDTO> all = depotService.getAllTransactions();
        assertThat(all).hasSize(2);
        // ordered by date desc -> newer first
        assertThat(all.get(0).getQuantity()).isEqualByComparingTo("0.02");

        List<TransactionDTO> forPosition = depotService.getTransactions(p.getId());
        assertThat(forPosition).hasSize(2);

        assertThat(depotService.getTransaction(older.getId())).isPresent();

        depotService.deleteTransaction(older.getId());
        assertThat(depotService.getTransaction(older.getId())).isEmpty();
        assertThat(depotService.getTransactionCount()).isEqualTo(1);

        depotService.deleteTransaction();
        assertThat(depotService.getTransactionCount()).isZero();
    }

    @Test
    void getHistory_returnsEmptyList_whenNoPriceHistorySeeded() {
        assertThat(depotService.getHistory()).isEmpty();
    }

    @Test
    void readCookie_returnsDefault_whenCookieMissing() {
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
        assertThat(depotService.readCookie(request, "depot-currency", "EUR")).isEqualTo("EUR");
    }

    @Test
    void readCookie_returnsCookieValue_whenPresent() {
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie("depot-currency", "USD"));
        assertThat(depotService.readCookie(request, "depot-currency", "EUR")).isEqualTo("USD");
    }

    @Test
    void toTransactionDTO_roundsQuantityFiat() {
        Position p = depotService.save(TestFixtures.position("Bybit", PositionType.EXCHANGE));
        Transaction tx = TestFixtures.buy(p, LocalDateTime.now(), new BigDecimal("0.001"), new BigDecimal("50000"), "EUR");
        tx.setQuantityFiat(new BigDecimal("50.005"));
        depotService.saveTransaction(tx);

        TransactionDTO dto = depotService.getTransaction(tx.getId())
                .map(depotService::toTransactionDTO)
                .orElseThrow();
        assertThat(dto.getQuantityFiat()).isEqualByComparingTo("50.01");
        assertThat(dto.getTransactionId()).isEqualTo(tx.getTransactionId());
    }

    @Test
    void sellTransaction_isTrackedAsRealized() {
        Position p = depotService.save(TestFixtures.position("Coinbase", PositionType.EXCHANGE));
        depotService.saveTransaction(TestFixtures.buy(p, LocalDateTime.now().minusDays(5),
                new BigDecimal("0.02"), new BigDecimal("50000"), "EUR"));
        Transaction sellTx = TestFixtures.sell(p, LocalDateTime.now().minusDays(1),
                new BigDecimal("0.01"), new BigDecimal("55000"), "EUR");
        depotService.saveTransaction(sellTx);

        PositionDTO dto = depotService.getAllPositions("EUR").get(0);
        assertThat(dto.getQuantity()).isEqualByComparingTo("0.01");
        assertThat(dto.getRealized()).isEqualByComparingTo(sellTx.getQuantityFiat());
    }
}
