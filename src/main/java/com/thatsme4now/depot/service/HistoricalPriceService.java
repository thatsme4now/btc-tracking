package com.thatsme4now.depot.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.thatsme4now.depot.dto.HistoricalPriceDTO;
import com.thatsme4now.depot.entity.HistoricalPrice;
import com.thatsme4now.depot.repository.HistoricalPriceRepository;
import com.thatsme4now.depot.repository.TransactionRepository;

import lombok.RequiredArgsConstructor;

/**
 * Lets the user view and manually set/correct the year-end (31.12.) BTC
 * reference price used by the "Bestandsansicht" page — see HistoricalPriceSeeder
 * for the initial, approximate seed data. This is the intended way to fill in
 * a year once it has elapsed but isn't covered by the seeder's hardcoded table
 * (e.g. next year, without needing an app update), or to correct a value.
 */
@Service
@RequiredArgsConstructor
public class HistoricalPriceService {

    private static final String TICKER = "BTC";

    /** Nearest mempool data point must be within this many days of 31.12., else the year is left unfilled — same tolerance as MonthlyPriceService. */
    private static final long TOLERANCE_DAYS = 2;
    /** Currencies filled by {@link #fillMissingFromMempool()} — same pair as the monthly fill. */
    private static final List<String> FILL_CURRENCIES = List.of("EUR", "USD");
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private final HistoricalPriceRepository historicalPriceRepo;
    private final TransactionRepository     transactionRepo;
    private final MempoolPriceService       mempoolPriceService;

    /** One row per past year (earliest transaction year .. last fully elapsed year), price null if not set yet. */
    public List<HistoricalPriceDTO> getYearly(String currency) {
        String cur = (currency == null || currency.isBlank()) ? "EUR" : currency.toUpperCase();
        int currentYear = LocalDate.now().getYear();

        int firstYear = transactionRepo.findFirstByOrderByDateAsc()
                .map(tx -> tx.getDate().getYear())
                .orElse(currentYear);

        List<HistoricalPriceDTO> result = new ArrayList<>();
        for (int y = firstYear; y < currentYear; y++) {
            HistoricalPriceDTO dto = new HistoricalPriceDTO();
            dto.setYear(y);
            dto.setCurrency(cur);
            dto.setPrice(historicalPriceRepo.findByTickerAndYearAndCurrency(TICKER, y, cur)
                    .map(HistoricalPrice::getPrice)
                    .orElse(null));
            result.add(dto);
        }
        return result;
    }

    /** Creates or updates the year-end reference price for one year/currency. */
    @Transactional
    public HistoricalPriceDTO upsert(Integer year, String currency, BigDecimal price) {
        if (year == null) throw new IllegalArgumentException("Year required");
        if (currency == null || currency.isBlank()) throw new IllegalArgumentException("Currency required");
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException("Invalid price");

        int currentYear = LocalDate.now().getYear();
        if (year >= currentYear) {
            throw new IllegalArgumentException("Only fully elapsed years can be set — the current year always uses the live price");
        }

        String cur = currency.toUpperCase();
        HistoricalPrice hp = historicalPriceRepo.findByTickerAndYearAndCurrency(TICKER, year, cur)
                .orElseGet(HistoricalPrice::new);
        hp.setTicker(TICKER);
        hp.setYear(year);
        hp.setCurrency(cur);
        hp.setPrice(price.setScale(2, RoundingMode.HALF_UP));
        historicalPriceRepo.save(hp);

        HistoricalPriceDTO dto = new HistoricalPriceDTO();
        dto.setYear(year);
        dto.setCurrency(cur);
        dto.setPrice(hp.getPrice());
        return dto;
    }

    /**
     * Fills gaps in the year-end (31.12., 23:59:59 Europe/Berlin) reference
     * price for EUR and USD, across every year shown by {@link #getYearly},
     * from the mempool instance configured in AppSettings — one
     * historical-price call per currency (full series), then the nearest
     * data point within {@link #TOLERANCE_DAYS} of each missing year's
     * 31.12. is picked. Existing rows (seeded or manual) are never touched.
     * The current (still-running) year is skipped — it always uses the live
     * price, same as {@link #getYearly} already excludes it.
     *
     * Throws MempoolPriceService.MempoolException (propagated) if mempool
     * isn't configured/reachable — the whole action fails with one clear
     * message rather than silently fetching only some currencies.
     */
    @Transactional
    public Map<String, Object> fillMissingFromMempool() {
        int currentYear = LocalDate.now().getYear();
        int firstYear = transactionRepo.findFirstByOrderByDateAsc()
                .map(tx -> tx.getDate().getYear())
                .orElse(currentYear);

        int filled = 0, alreadyPresent = 0, notFound = 0;

        for (String cur : FILL_CURRENCIES) {
            List<MempoolPriceService.PricePoint> series = mempoolPriceService.fetchHistoricalSeries(cur);

            for (int year = firstYear; year < currentYear; year++) {
                if (historicalPriceRepo.existsByTickerAndYearAndCurrency(TICKER, year, cur)) {
                    alreadyPresent++;
                    continue;
                }

                Instant yearEnd = LocalDate.of(year, Month.DECEMBER, 31).atTime(23, 59, 59).atZone(ZONE).toInstant();
                MempoolPriceService.PricePoint nearest = findNearest(series, yearEnd);
                if (nearest == null || Duration.between(nearest.time(), yearEnd).abs().toDays() > TOLERANCE_DAYS) {
                    notFound++;
                    continue;
                }

                HistoricalPrice hp = new HistoricalPrice();
                hp.setTicker(TICKER);
                hp.setYear(year);
                hp.setCurrency(cur);
                hp.setPrice(nearest.price().setScale(2, RoundingMode.HALF_UP));
                historicalPriceRepo.save(hp);
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
