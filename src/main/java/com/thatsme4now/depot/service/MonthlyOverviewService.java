package com.thatsme4now.depot.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.stereotype.Service;

import com.thatsme4now.depot.dto.MonthlyBalanceDTO;
import com.thatsme4now.depot.dto.YearlyOverviewDTO;
import com.thatsme4now.depot.entity.Transaction;
import com.thatsme4now.depot.repository.MonthlyPriceRepository;
import com.thatsme4now.depot.repository.TransactionRepository;

import lombok.RequiredArgsConstructor;

/**
 * Computes the Bestand (BTC balance) / Wertentwicklung (market value) monthly
 * series for the "Jahresansicht" line chart — either scoped to one year, or
 * across the full history ("Gesamtansicht").
 *
 * Unlike HoldingsYearlyService, this only tracks raw BTC quantity (no cost
 * basis / realized-unrealized G/V) — "Wertentwicklung" here is a plain
 * market-value snapshot (balance × month-end price), not a profit/loss
 * figure. Deliberately its own simple chronological walk rather than reusing
 * HoldingsYearlyService, since the two serve different, independent charts.
 */
@Service
@RequiredArgsConstructor
public class MonthlyOverviewService {

    private static final String TICKER = "BTC";

    private final TransactionRepository   transactionRepo;
    private final MonthlyPriceRepository  monthlyPriceRepo;
    private final DepotService            depotService;

    /**
     * Builds the monthly BTC balance / market value series for a given year,
     * or across the full history if {@code year} is null.
     */
    public YearlyOverviewDTO getOverview(Integer year, String currency) {
        String cur = (currency == null || currency.isBlank()) ? "EUR" : currency.toUpperCase();

        List<Transaction> txs = transactionRepo.findAll().stream()
                .sorted(Comparator.comparing(Transaction::getDate))
                .toList();

        YearMonth currentYm = YearMonth.now();
        TreeSet<Integer> years = new TreeSet<>();
        txs.forEach(tx -> years.add(tx.getDate().getYear()));
        years.add(currentYm.getYear()); // always offer the current year even without transactions yet

        YearMonth rangeFrom;
        YearMonth rangeTo;
        if (year != null) {
            rangeFrom = YearMonth.of(year, 1);
            rangeTo   = (year == currentYm.getYear()) ? currentYm : YearMonth.of(year, 12);
        } else {
            YearMonth firstTxYm = txs.isEmpty() ? currentYm : YearMonth.from(txs.get(0).getDate());
            rangeFrom = firstTxYm.isBefore(YearMonth.of(2013, 1)) ? YearMonth.of(2013, 1) : firstTxYm;
            rangeTo   = currentYm;
        }

        // Running balance snapshot as of the last transaction seen in each month.
        Map<YearMonth, BigDecimal> monthEndQty = new HashMap<>();
        BigDecimal runningQty = BigDecimal.ZERO;
        for (Transaction tx : txs) {
            YearMonth ym = YearMonth.from(tx.getDate());
            switch (tx.getType()) {
                case BUY, TRANSFER_IN, DEPOSIT -> runningQty = runningQty.add(tx.getQuantity());
                case SELL, TRANSFER_OUT, WITHDRAW -> runningQty = runningQty.subtract(tx.getQuantity());
            }
            monthEndQty.put(ym, runningQty);
        }

        List<MonthlyBalanceDTO> series = new ArrayList<>();
        BigDecimal carryQty = BigDecimal.ZERO;
        // Forward-fill from the very start up to rangeFrom, so a range starting after
        // the first transaction still begins with the correct carried-over balance.
        YearMonth prefillStart = YearMonth.from(txs.isEmpty() ? LocalDate.now() : txs.get(0).getDate());
        for (YearMonth ym = prefillStart; ym.isBefore(rangeFrom); ym = ym.plusMonths(1)) {
            if (monthEndQty.containsKey(ym)) carryQty = monthEndQty.get(ym);
        }

        for (YearMonth ym = rangeFrom; !ym.isAfter(rangeTo); ym = ym.plusMonths(1)) {
            if (monthEndQty.containsKey(ym)) carryQty = monthEndQty.get(ym);

            MonthlyBalanceDTO dto = new MonthlyBalanceDTO();
            dto.setYear(ym.getYear());
            dto.setMonth(ym.getMonthValue());
            dto.setBtcBalance(carryQty.setScale(8, RoundingMode.HALF_UP));
            boolean isCurrent = ym.equals(currentYm);
            dto.setCurrent(isCurrent);

            BigDecimal price = isCurrent ? liveCurrentPrice(cur) : monthEndPrice(ym, cur);
            if (price != null) {
                dto.setValue(carryQty.multiply(price).setScale(2, RoundingMode.HALF_UP));
            }
            series.add(dto);
        }

        YearlyOverviewDTO result = new YearlyOverviewDTO();
        result.setYear(year);
        result.setAvailableYears(new ArrayList<>(years));
        result.setSeries(series);
        return result;
    }

    /** Looks up the stored month-end reference price, or null if not set. */
    private BigDecimal monthEndPrice(YearMonth ym, String currency) {
        return monthlyPriceRepo.findByTickerAndYearAndMonthAndCurrency(TICKER, ym.getYear(), ym.getMonthValue(), currency)
                .map(mp -> mp.getPrice())
                .orElse(null);
    }

    private BigDecimal liveCurrentPrice(String currency) {
        return depotService.getCurrentPrice(currency)
                .map(cp -> cp.getPrice())
                .orElse(null);
    }
}
