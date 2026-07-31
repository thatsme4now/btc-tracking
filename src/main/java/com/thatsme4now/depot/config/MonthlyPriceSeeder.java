package com.thatsme4now.depot.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.thatsme4now.depot.entity.MonthlyPrice;
import com.thatsme4now.depot.repository.MonthlyPriceRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Seeds monthly (Ultimo) BTC reference prices for EUR/USD/THB from the
 * bundled CSV resource data/monthly-btc-prices.csv.
 *
 * CSV format (pivot, one row per month): year,month,eur,usd,thb — any cell
 * can be left blank (not yet filled in) and is simply skipped; it can be
 * added later without needing a rebuild, since this seeder re-reads the
 * file and re-checks for missing rows on every startup.
 *
 * Like HistoricalPriceSeeder, this NEVER overwrites a row that already
 * exists for a given (ticker, year, month, currency) — whether it came
 * from an earlier seed run, a live CoinGecko backfill (see
 * MonthlyPriceService#backfill), or a manual correction via the UI.
 *
 * The currently running (not yet elapsed) month is intentionally skipped —
 * it always uses the live current_price instead, see MonthlyPriceService.
 */
@Slf4j
@Component
public class MonthlyPriceSeeder {

    private static final String TICKER   = "BTC";
    private static final String CSV_PATH = "data/monthly-btc-prices.csv";
    private static final String[] CSV_CURRENCY_COLUMNS = { "eur", "usd", "thb" };

    private final MonthlyPriceRepository monthlyPriceRepo;

    public MonthlyPriceSeeder(MonthlyPriceRepository monthlyPriceRepo) {
        this.monthlyPriceRepo = monthlyPriceRepo;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedMonthlyPrices() {
        LocalDate now = LocalDate.now();
        int currentYear  = now.getYear();
        int currentMonth = now.getMonthValue();

        ClassPathResource resource = new ClassPathResource(CSV_PATH);
        if (!resource.exists()) {
            log.warn("Monthly price seed file not found on classpath: {}", CSV_PATH);
            return;
        }

        int inserted = 0;
        int lineNo   = 0;
        try (InputStream in = resource.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

            String header = reader.readLine(); // "year,month,eur,usd,thb"
            lineNo++;
            if (header == null) return;
            String[] columns = header.trim().split(",");

            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) continue;
                String[] cells = line.split(",", -1); // -1 keeps trailing empty cells

                Integer year  = parseInt(cells, 0);
                Integer month = parseInt(cells, 1);
                if (year == null || month == null) {
                    log.warn("Skipping malformed monthly price CSV row {}: {}", lineNo, line);
                    continue;
                }
                if (year == currentYear && month == currentMonth) {
                    continue; // running month → always the live current_price, never seeded
                }

                for (int col = 0; col < CSV_CURRENCY_COLUMNS.length; col++) {
                    String currency  = CSV_CURRENCY_COLUMNS[col].toUpperCase();
                    int    cellIndex = 2 + col;
                    BigDecimal price = parsePrice(cells, cellIndex);
                    if (price == null) continue; // blank cell — not filled in yet

                    if (monthlyPriceRepo.existsByTickerAndYearAndMonthAndCurrency(TICKER, year, month, currency)) {
                        continue; // already present — never overwrite
                    }
                    MonthlyPrice mp = new MonthlyPrice();
                    mp.setTicker(TICKER);
                    mp.setYear(year);
                    mp.setMonth(month);
                    mp.setCurrency(currency);
                    mp.setPrice(price);
                    monthlyPriceRepo.save(mp);
                    inserted++;
                }
            }
        } catch (IOException e) {
            log.error("Failed to read monthly price seed file {}: {}", CSV_PATH, e.getMessage());
            return;
        }

        if (inserted > 0) {
            log.info("Seeded {} monthly price row(s) from {}.", inserted, CSV_PATH);
        }
    }

    private Integer parseInt(String[] cells, int index) {
        if (index >= cells.length) return null;
        String raw = cells[index].trim();
        if (raw.isEmpty()) return null;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal parsePrice(String[] cells, int index) {
        if (index >= cells.length) return null;
        String raw = cells[index].trim();
        if (raw.isEmpty()) return null;
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            log.warn("Skipping unparseable price value '{}' at column {}", raw, index);
            return null;
        }
    }
}
