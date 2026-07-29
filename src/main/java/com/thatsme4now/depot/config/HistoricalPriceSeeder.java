package com.thatsme4now.depot.config;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.thatsme4now.depot.entity.HistoricalPrice;
import com.thatsme4now.depot.repository.HistoricalPriceRepository;
import com.thatsme4now.depot.repository.TransactionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Seeds year-end (31.12.) BTC reference prices for EUR/USD/THB, for every
 * past year that this depot could plausibly have transactions in.
 *
 * Unlike {@link DataInitializer}, this does NOT gate on current_price being
 * empty — it runs on every startup (also against an already-populated,
 * long-running instance) and only inserts rows that don't exist yet for a
 * given (ticker, year, currency). Cheap no-op once fully seeded, and picks
 * up newly-elapsed years automatically on later app updates once the
 * REFERENCE_PRICES_USD map below is extended.
 *
 * Values are approximate, publicly documented year-end closing prices in
 * USD (sourced from public BTC price history aggregators), converted to
 * EUR/THB via approximate historical FX rates at the same date. They are
 * NOT exact tick-level closes. This table only feeds informational /
 * future visualizations for past years (the "Bestandsansicht" chart uses
 * the live current_price for the running year, never this table) — if
 * more precision is ever needed, rows can simply be corrected in the DB.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HistoricalPriceSeeder {

    private final HistoricalPriceRepository historicalPriceRepo;
    private final TransactionRepository     transactionRepo;

    private static final String TICKER = "BTC";
    private static final String[] CURRENCIES = { "EUR", "USD", "THB" };

    /** Approximate BTC/USD closing price on 31.12. of each year. */
    private static final Map<Integer, BigDecimal> USD_YEAR_END = new LinkedHashMap<>();
    /** Approximate USD→EUR rate (USD per 1 EUR) on 31.12. of each year, for conversion. */
    private static final Map<Integer, BigDecimal> USD_PER_EUR = new LinkedHashMap<>();
    /** Approximate THB per 1 USD on 31.12. of each year, for conversion. */
    private static final Map<Integer, BigDecimal> THB_PER_USD = new LinkedHashMap<>();

    static {
        USD_YEAR_END.put(2013, new BigDecimal("751.00"));
        USD_YEAR_END.put(2014, new BigDecimal("320.00"));
        USD_YEAR_END.put(2015, new BigDecimal("430.00"));
        USD_YEAR_END.put(2016, new BigDecimal("963.00"));
        USD_YEAR_END.put(2017, new BigDecimal("13850.00"));
        USD_YEAR_END.put(2018, new BigDecimal("3742.00"));
        USD_YEAR_END.put(2019, new BigDecimal("7200.00"));
        USD_YEAR_END.put(2020, new BigDecimal("28990.00"));
        USD_YEAR_END.put(2021, new BigDecimal("46200.00"));
        USD_YEAR_END.put(2022, new BigDecimal("16540.00"));
        USD_YEAR_END.put(2023, new BigDecimal("42265.00"));
        USD_YEAR_END.put(2024, new BigDecimal("93429.00"));
        USD_YEAR_END.put(2025, new BigDecimal("87000.00"));

        USD_PER_EUR.put(2013, new BigDecimal("1.379"));
        USD_PER_EUR.put(2014, new BigDecimal("1.210"));
        USD_PER_EUR.put(2015, new BigDecimal("1.086"));
        USD_PER_EUR.put(2016, new BigDecimal("1.052"));
        USD_PER_EUR.put(2017, new BigDecimal("1.201"));
        USD_PER_EUR.put(2018, new BigDecimal("1.145"));
        USD_PER_EUR.put(2019, new BigDecimal("1.121"));
        USD_PER_EUR.put(2020, new BigDecimal("1.221"));
        USD_PER_EUR.put(2021, new BigDecimal("1.137"));
        USD_PER_EUR.put(2022, new BigDecimal("1.070"));
        USD_PER_EUR.put(2023, new BigDecimal("1.104"));
        USD_PER_EUR.put(2024, new BigDecimal("1.035"));
        USD_PER_EUR.put(2025, new BigDecimal("1.050"));

        THB_PER_USD.put(2013, new BigDecimal("32.9"));
        THB_PER_USD.put(2014, new BigDecimal("32.9"));
        THB_PER_USD.put(2015, new BigDecimal("36.0"));
        THB_PER_USD.put(2016, new BigDecimal("35.8"));
        THB_PER_USD.put(2017, new BigDecimal("32.6"));
        THB_PER_USD.put(2018, new BigDecimal("32.6"));
        THB_PER_USD.put(2019, new BigDecimal("30.0"));
        THB_PER_USD.put(2020, new BigDecimal("30.0"));
        THB_PER_USD.put(2021, new BigDecimal("33.4"));
        THB_PER_USD.put(2022, new BigDecimal("34.6"));
        THB_PER_USD.put(2023, new BigDecimal("34.1"));
        THB_PER_USD.put(2024, new BigDecimal("34.0"));
        THB_PER_USD.put(2025, new BigDecimal("32.5"));
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedHistoricalPrices() {
        int currentYear = LocalDate.now().getYear();

        int earliestYear = transactionRepo.findFirstByOrderByDateAsc()
                .map(tx -> tx.getDate().getYear())
                .orElse(currentYear);

        // Only years we actually have reference data for, and only years
        // that have already fully elapsed (not the running year — that
        // always uses the live current_price instead).
        int fromYear = Math.max(earliestYear, USD_YEAR_END.keySet().stream().min(Integer::compareTo).orElse(currentYear));
        int toYear   = Math.min(currentYear - 1, USD_YEAR_END.keySet().stream().max(Integer::compareTo).orElse(currentYear - 1));

        int inserted = 0;
        for (int year = fromYear; year <= toYear; year++) {
            BigDecimal usd = USD_YEAR_END.get(year);
            if (usd == null) continue; // no reference data for this year

            for (String currency : CURRENCIES) {
                if (historicalPriceRepo.existsByTickerAndYearAndCurrency(TICKER, year, currency)) {
                    continue; // already seeded (or manually corrected) — never overwrite
                }
                BigDecimal price = switch (currency) {
                    case "USD" -> usd;
                    case "EUR" -> usd.divide(USD_PER_EUR.getOrDefault(year, BigDecimal.ONE), 2, java.math.RoundingMode.HALF_UP);
                    case "THB" -> usd.multiply(THB_PER_USD.getOrDefault(year, BigDecimal.ONE)).setScale(2, java.math.RoundingMode.HALF_UP);
                    default -> null;
                };
                if (price == null) continue;

                HistoricalPrice hp = new HistoricalPrice();
                hp.setTicker(TICKER);
                hp.setYear(year);
                hp.setCurrency(currency);
                hp.setPrice(price);
                historicalPriceRepo.save(hp);
                inserted++;
            }
        }

        if (inserted > 0) {
            log.info("Seeded {} historical year-end price row(s) ({}–{}).", inserted, fromYear, toYear);
        }
    }
}
