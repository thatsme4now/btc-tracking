package com.thatsme4now.depot.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.thatsme4now.depot.dto.MonthlyPriceDTO;
import com.thatsme4now.depot.entity.MonthlyPrice;
import com.thatsme4now.depot.repository.MonthlyPriceRepository;
import com.thatsme4now.depot.repository.TransactionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Lets the user view/correct the monthly (Ultimo) BTC reference price used by
 * the "Jahresansicht" visualization. Values come from the bundled CSV seed
 * (see MonthlyPriceSeeder) plus manual corrections via the UI — no live
 * network calls (app runs fully offline).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonthlyPriceService {

    private static final String TICKER = "BTC";

    /** Nearest mempool data point must be within this many days of the requested Ultimo date, else the month is left unfilled. */
    private static final long TOLERANCE_DAYS = 2;
    /** Currencies filled by {@link #fillMissingFromMempool(int)} — same pair as the current-price mempool fetch. */
    private static final List<String> FILL_CURRENCIES = List.of("EUR", "USD");
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private final MonthlyPriceRepository monthlyPriceRepo;
    private final TransactionRepository  transactionRepo;
    private final DepotService           depotService;
    private final MempoolPriceService    mempoolPriceService;

    /** One row per month (earliest transaction month .. current month) for the manual price dialog. */
    public List<MonthlyPriceDTO> getMonthly(String currency) {
        String cur = (currency == null || currency.isBlank()) ? "EUR" : currency.toUpperCase();
        LocalDate now = LocalDate.now();
        YearMonth currentYm = YearMonth.from(now);

        YearMonth firstYm = transactionRepo.findFirstByOrderByDateAsc()
                .map(tx -> YearMonth.from(tx.getDate()))
                .orElse(currentYm);
        // Never show earlier than the bundled CSV seed's history start.
        YearMonth from = firstYm.isBefore(YearMonth.of(2013, 1)) ? YearMonth.of(2013, 1) : firstYm;

        List<MonthlyPriceDTO> result = new ArrayList<>();
        for (YearMonth ym = from; !ym.isAfter(currentYm); ym = ym.plusMonths(1)) {
            MonthlyPriceDTO dto = new MonthlyPriceDTO();
            dto.setYear(ym.getYear());
            dto.setMonth(ym.getMonthValue());
            dto.setCurrency(cur);
            dto.setCurrent(ym.equals(currentYm));
            if (!dto.isCurrent()) {
                dto.setPrice(monthlyPriceRepo.findByTickerAndYearAndMonthAndCurrency(TICKER, ym.getYear(), ym.getMonthValue(), cur)
                        .map(MonthlyPrice::getPrice)
                        .orElse(null));
            }
            result.add(dto);
        }
        return result;
    }

    /**
     * Plain price history for the "overall view" BTC price chart — every month
     * present in the monthly_price table for this currency, plus the current
     * month with the live price. Deliberately NOT limited to the range of the
     * user's own transactions (unlike {@link #getMonthly} /
     * {@link MonthlyOverviewService#getOverview}), since this chart should
     * show the full available historical price curve, including years before
     * the user's own entry. Independent query rather than reusing
     * getOverview()'s range logic.
     */
    public List<MonthlyPriceDTO> getPriceHistory(String currency) {
        String cur = (currency == null || currency.isBlank()) ? "EUR" : currency.toUpperCase();
        YearMonth currentYm = YearMonth.now();

        List<MonthlyPriceDTO> result = new ArrayList<>();
        for (MonthlyPrice mp : monthlyPriceRepo.findByTickerAndCurrencyOrderByYearAscMonthAsc(TICKER, cur)) {
            YearMonth ym = YearMonth.of(mp.getYear(), mp.getMonth());
            if (!ym.isBefore(currentYm)) continue; // current/future month is added below with the live price
            MonthlyPriceDTO dto = new MonthlyPriceDTO();
            dto.setYear(mp.getYear());
            dto.setMonth(mp.getMonth());
            dto.setCurrency(cur);
            dto.setPrice(mp.getPrice());
            dto.setCurrent(false);
            result.add(dto);
        }

        MonthlyPriceDTO currentDto = new MonthlyPriceDTO();
        currentDto.setYear(currentYm.getYear());
        currentDto.setMonth(currentYm.getMonthValue());
        currentDto.setCurrency(cur);
        currentDto.setCurrent(true);
        currentDto.setPrice(depotService.getCurrentPrice(cur).map(cp -> cp.getPrice()).orElse(null));
        result.add(currentDto);

        return result;
    }

    /** Creates or updates the manual reference price for one month/currency. */
    @Transactional
    public MonthlyPriceDTO upsert(Integer year, Integer month, String currency, BigDecimal price) {
        if (year == null || month == null) throw new IllegalArgumentException("Year and month required");
        if (month < 1 || month > 12) throw new IllegalArgumentException("Invalid month");
        if (currency == null || currency.isBlank()) throw new IllegalArgumentException("Currency required");
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException("Invalid price");

        YearMonth ym = YearMonth.of(year, month);
        if (!ym.isBefore(YearMonth.now())) {
            throw new IllegalArgumentException("Only fully elapsed months can be set — the running month always uses the live price");
        }

        String cur = currency.toUpperCase();
        MonthlyPrice mp = monthlyPriceRepo.findByTickerAndYearAndMonthAndCurrency(TICKER, year, month, cur)
                .orElseGet(MonthlyPrice::new);
        mp.setTicker(TICKER);
        mp.setYear(year);
        mp.setMonth(month);
        mp.setCurrency(cur);
        mp.setPrice(price.setScale(2, RoundingMode.HALF_UP));
        mp.setSource("MANUAL"); // explicit manual correction always resets a prior MEMPOOL-filled value back to MANUAL
        mp.setLoadedAt(LocalDateTime.now());
        monthlyPriceRepo.save(mp);

        MonthlyPriceDTO dto = new MonthlyPriceDTO();
        dto.setYear(year);
        dto.setMonth(month);
        dto.setCurrency(cur);
        dto.setPrice(mp.getPrice());
        return dto;
    }

    /**
     * Fills gaps in the given year's monthly (Ultimo) reference price for
     * EUR and USD from the mempool instance configured in AppSettings —
     * one historical-price call per currency (no timestamp, i.e. the full
     * series), then the nearest data point within {@link #TOLERANCE_DAYS}
     * of each missing month's Ultimo (23:59:59, Europe/Berlin, last
     * calendar day of the month) is picked. Existing rows (manual, seeded,
     * or from an earlier mempool fill) are never touched. The running/
     * future month is skipped — it always uses the live current price.
     *
     * Throws MempoolPriceService.MempoolException (propagated) if mempool
     * isn't configured/reachable — the whole action fails with one clear
     * message rather than silently fetching only some currencies.
     */
    @Transactional
    public Map<String, Object> fillMissingFromMempool(int year) {
        YearMonth currentYm = YearMonth.now();
        int filled = 0, alreadyPresent = 0, notFound = 0;

        for (String cur : FILL_CURRENCIES) {
            List<MempoolPriceService.PricePoint> series = mempoolPriceService.fetchHistoricalSeries(cur);

            for (int month = 1; month <= 12; month++) {
                YearMonth ym = YearMonth.of(year, month);
                if (!ym.isBefore(currentYm)) continue; // running/future month → always live current price

                if (monthlyPriceRepo.existsByTickerAndYearAndMonthAndCurrency(TICKER, year, month, cur)) {
                    alreadyPresent++;
                    continue;
                }

                Instant ultimo = ym.atEndOfMonth().atTime(23, 59, 59).atZone(ZONE).toInstant();
                MempoolPriceService.PricePoint nearest = findNearest(series, ultimo);
                if (nearest == null || Duration.between(nearest.time(), ultimo).abs().toDays() > TOLERANCE_DAYS) {
                    notFound++;
                    continue;
                }

                MonthlyPrice mp = new MonthlyPrice();
                mp.setTicker(TICKER);
                mp.setYear(year);
                mp.setMonth(month);
                mp.setCurrency(cur);
                mp.setPrice(nearest.price().setScale(2, RoundingMode.HALF_UP));
                mp.setSource("MEMPOOL");
                mp.setLoadedAt(LocalDateTime.now());
                monthlyPriceRepo.save(mp);
                filled++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("filled", filled);
        result.put("alreadyPresent", alreadyPresent);
        result.put("notFound", notFound);
        result.put("currencies", FILL_CURRENCIES);
        return result;
    }

    private MempoolPriceService.PricePoint findNearest(List<MempoolPriceService.PricePoint> series, Instant target) {
        MempoolPriceService.PricePoint best = null;
        long bestDiffSeconds = Long.MAX_VALUE;
        for (MempoolPriceService.PricePoint p : series) {
            long diff = Math.abs(Duration.between(p.time(), target).getSeconds());
            if (diff < bestDiffSeconds) {
                bestDiffSeconds = diff;
                best = p;
            }
        }
        return best;
    }

}
