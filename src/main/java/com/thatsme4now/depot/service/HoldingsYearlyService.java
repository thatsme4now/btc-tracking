package com.thatsme4now.depot.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

import com.thatsme4now.depot.dto.PortfolioMetricsDTO;
import com.thatsme4now.depot.dto.PositionDTO;
import com.thatsme4now.depot.dto.YearlyHoldingsDTO;
import com.thatsme4now.depot.entity.Transaction;
import com.thatsme4now.depot.repository.HistoricalPriceRepository;
import com.thatsme4now.depot.repository.TransactionRepository;

import lombok.RequiredArgsConstructor;

/**
 * Computes the per-year data for the "Bestandsansicht" visualization:
 * yearly buys (stacked by exchange/wallet), sells, realized gain/loss,
 * BTC balance and unrealized gain/loss as of 31.12. of every year.
 *
 * Realized gain/loss uses a portfolio-wide weighted-average cost basis,
 * walking all transactions chronologically. This mirrors the "average
 * purchase price" approach already used elsewhere in DepotService (no
 * per-position or FIFO lot matching), just applied across the whole depot
 * and split into yearly buckets.
 *
 * BUY/SELL affect both quantity and cost basis. TRANSFER_IN/OUT and
 * DEPOSIT/WITHDRAW affect only quantity (never cost) — a transfer between
 * your own positions nets to zero across the whole portfolio except for
 * any fee paid in BTC, which correctly shows up as a small reduction in
 * total holdings (and therefore a slightly higher average cost per
 * remaining BTC), without being a "sale".
 */
@Service
@RequiredArgsConstructor
public class HoldingsYearlyService {

    private static final String TICKER = "BTC";

    private final TransactionRepository       transactionRepo;
    private final HistoricalPriceRepository   historicalPriceRepo;
    private final DepotService                depotService;

    /** Computes the yearly buys/sells/realized-unrealized breakdown for the "holdings" chart. */
    public List<YearlyHoldingsDTO> getYearlyHoldings(String currency) {
        String cur = (currency == null || currency.isBlank()) ? "EUR" : currency.toUpperCase();

        List<Transaction> txs = transactionRepo.findAll().stream()
                .sorted(Comparator.comparing(Transaction::getDate))
                .toList();

        int currentYear = LocalDate.now().getYear();

        // year -> DTO, pre-filled so years with no activity still show up as empty bars
        Map<Integer, YearlyHoldingsDTO> byYear = new TreeMap<>();
        int firstYear = txs.isEmpty() ? currentYear : txs.get(0).getDate().getYear();
        for (int y = firstYear; y <= currentYear; y++) {
            YearlyHoldingsDTO dto = new YearlyHoldingsDTO();
            dto.setYear(y);
            dto.setCurrentYear(y == currentYear);
            byYear.put(y, dto);
        }

        BigDecimal runningQty  = BigDecimal.ZERO;
        BigDecimal runningCost = BigDecimal.ZERO; // in display currency

        // Snapshot of running qty/cost as of the last transaction seen in each year
        Map<Integer, BigDecimal> yearEndQty  = new HashMap<>();
        Map<Integer, BigDecimal> yearEndCost = new HashMap<>();

        for (Transaction tx : txs) {
            int year = tx.getDate().getYear();
            YearlyHoldingsDTO dto = byYear.get(year);

            switch (tx.getType()) {
                case BUY -> {
                    BigDecimal cost = fiatValue(tx, cur);
                    if (cost != null) {
                        runningQty  = runningQty.add(tx.getQuantity());
                        runningCost = runningCost.add(cost);

                        String label = tx.getPosition() != null ? tx.getPosition().getLabel() : "?";
                        dto.getBuysByExchange().merge(label, cost, BigDecimal::add);
                        dto.setTotalBuys(dto.getTotalBuys().add(cost));
                    }
                }
                case SELL -> {
                    BigDecimal proceeds = sellProceeds(tx, cur);
                    if (proceeds != null) {
                        BigDecimal costOfSold;
                        if (runningQty.compareTo(BigDecimal.ZERO) > 0) {
                            BigDecimal avgCost = runningCost.divide(runningQty, 8, RoundingMode.HALF_UP);
                            costOfSold = tx.getQuantity().multiply(avgCost);
                            if (costOfSold.compareTo(runningCost) > 0) costOfSold = runningCost; // clamp rounding drift
                        } else {
                            costOfSold = BigDecimal.ZERO; // selling with no tracked cost basis (data gap)
                        }

                        BigDecimal gain = proceeds.subtract(costOfSold);
                        dto.setTotalSells(dto.getTotalSells().add(proceeds));
                        dto.setRealizedPnl(dto.getRealizedPnl().add(gain));

                        // Do NOT clamp runningQty to 0 here — the main page (DepotService.toDTO)
                        // sums quantity simply and unclamped across all transactions. Clamping
                        // on a transient dip below 0 (e.g. a TRANSFER_OUT/SELL dated before its
                        // matching TRANSFER_IN) would permanently lose that quantity and make
                        // the final balance diverge from the true unclamped total.
                        runningQty  = runningQty.subtract(tx.getQuantity());
                        runningCost = runningCost.subtract(costOfSold);
                        if (runningCost.compareTo(BigDecimal.ZERO) < 0) runningCost = BigDecimal.ZERO;
                    }
                }
                case TRANSFER_IN, DEPOSIT -> runningQty = runningQty.add(tx.getQuantity());
                case TRANSFER_OUT, WITHDRAW -> runningQty = runningQty.subtract(tx.getQuantity());
            }

            yearEndQty.put(year, runningQty);
            yearEndCost.put(year, runningCost);
        }

        // Forward-fill balance/cost across years with no transactions at all, then
        // derive BTC balance and unrealized gain/loss (31.12. price, or live for the current year).
        BigDecimal carryQty  = BigDecimal.ZERO;
        BigDecimal carryCost = BigDecimal.ZERO;
        for (int y = firstYear; y <= currentYear; y++) {
            if (yearEndQty.containsKey(y)) {
                carryQty  = yearEndQty.get(y);
                carryCost = yearEndCost.get(y);
            }
            YearlyHoldingsDTO dto = byYear.get(y);
            dto.setBtcBalance(carryQty.setScale(8, RoundingMode.HALF_UP));

            BigDecimal price = (y == currentYear) ? liveCurrentPrice(cur) : historicalYearEndPrice(y, cur);
            if (price != null) {
                BigDecimal unrealized = carryQty.multiply(price).subtract(carryCost);
                dto.setUnrealizedPnl(unrealized.setScale(2, RoundingMode.HALF_UP));
            }
        }

        // Round display amounts
        List<YearlyHoldingsDTO> result = new ArrayList<>();
        for (YearlyHoldingsDTO dto : byYear.values()) {
            Map<String, BigDecimal> rounded = new LinkedHashMap<>();
            dto.getBuysByExchange().forEach((k, v) -> rounded.put(k, v.setScale(2, RoundingMode.HALF_UP)));
            dto.setBuysByExchange(rounded);
            dto.setTotalBuys(dto.getTotalBuys().setScale(2, RoundingMode.HALF_UP));
            dto.setTotalSells(dto.getTotalSells().setScale(2, RoundingMode.HALF_UP));
            dto.setRealizedPnl(dto.getRealizedPnl().setScale(2, RoundingMode.HALF_UP));
            result.add(dto);
        }
        return result;
    }

    /**
     * Portfolio-wide metrics tile — computed once and shared by both the overview page
     * and the holdings page (GET /api/btc-tracking/metrics) so the numbers aren't derived
     * independently in two places. Lives here rather than in DepotService because DepotService
     * is referenced from here already; the reverse dependency would create a circular bean
     * reference. Realized/unrealized/gainLoss/performance deliberately reuse the same
     * portfolio-wide calculation as {@link #getYearlyHoldings}, not a per-position sum.
     */
    public PortfolioMetricsDTO computePortfolioMetrics(String currency) {
        String cur = (currency == null || currency.isBlank()) ? "EUR" : currency.toUpperCase();

        List<PositionDTO> positions = depotService.getAllPositions(cur);

        BigDecimal totalValue = positions.stream()
            .map(PositionDTO::getTotalValue)
            .filter(v -> v != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal invested = positions.stream()
            .map(PositionDTO::getInvested)
            .filter(v -> v != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalBtc = positions.stream()
            .map(PositionDTO::getQuantity)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<YearlyHoldingsDTO> yearly = getYearlyHoldings(cur);

        BigDecimal realized = yearly.stream()
                .map(YearlyHoldingsDTO::getRealizedPnl)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal unrealized = yearly.stream()
                .filter(YearlyHoldingsDTO::isCurrentYear)
                .map(YearlyHoldingsDTO::getUnrealizedPnl)
                .filter(v -> v != null)
                .findFirst()
                .orElse(BigDecimal.ZERO);

        BigDecimal gainLoss = realized.add(unrealized);
        BigDecimal performancePct = invested.compareTo(BigDecimal.ZERO) > 0
            ? gainLoss.divide(invested, 4, RoundingMode.HALF_UP)
                      .multiply(BigDecimal.valueOf(100))
                      .setScale(2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

        BigDecimal totalSats = totalBtc.multiply(BigDecimal.valueOf(100_000_000))
            .setScale(0, RoundingMode.HALF_UP);

        PortfolioMetricsDTO dto = new PortfolioMetricsDTO();
        dto.setTotalBtc(totalBtc);
        dto.setTotalSats(totalSats);
        dto.setTotalValue(totalValue);
        dto.setInvested(invested);
        dto.setRealized(realized);
        dto.setGainLoss(gainLoss);
        dto.setPerformancePct(performancePct);
        dto.setTransactionCount(depotService.getTransactionCount());
        return dto;
    }

    /** Looks up the stored 31.12. reference price for the given year, or null if not set. */
    private BigDecimal historicalYearEndPrice(int year, String currency) {
        return historicalPriceRepo.findByTickerAndYearAndCurrency(TICKER, year, currency)
                .map(hp -> hp.getPrice())
                .orElse(null);
    }

    private BigDecimal liveCurrentPrice(String currency) {
        return depotService.getCurrentPrice(currency)
                .map(cp -> cp.getPrice())
                .orElse(null);
    }

    /**
     * BUY value in the given display currency — same convention as DepotService.toDTO:
     * if the transaction's own currency already matches, pricePerBtc is used directly
     * (it is stored in the transaction's own currency); otherwise the transaction's
     * manually-set exchangeRate (to the currency active when it was entered) is applied.
     * Buy fees count toward the cost basis, consistent with the main table's gain/loss
     * column and the "gain/loss per buy" chart.
     */
    private BigDecimal fiatValue(Transaction tx, String displayCurrency) {
        if (tx.getPricePerBtc() == null) return null;
        BigDecimal rate = tx.getExchangeRate() != null ? tx.getExchangeRate() : BigDecimal.ONE;
        BigDecimal fees = tx.getFees() != null ? tx.getFees() : BigDecimal.ZERO;
        BigDecimal cost = tx.getQuantity().multiply(tx.getPricePerBtc()).add(fees);
        if (displayCurrency.equals(tx.getCurrency())) {
            return cost;
        }
        return cost.multiply(rate);
    }

    /** SELL proceeds in the given display currency — same convention as DepotService.toDTO's "realized".
     *  quantityFiat is already a total fiat amount (quantity × price), so converting currency only
     *  needs the exchange rate — NOT another multiplication by pricePerBtc (that previously squared
     *  the price dimension and produced a wildly wrong number for any cross-currency SELL). */
    private BigDecimal sellProceeds(Transaction tx, String displayCurrency) {
        if (tx.getQuantityFiat() == null) return null;
        BigDecimal rate = tx.getExchangeRate() != null ? tx.getExchangeRate() : BigDecimal.ONE;
        if (displayCurrency.equals(tx.getCurrency())) {
            return tx.getQuantityFiat();
        }
        return tx.getQuantityFiat().multiply(rate);
    }
}
