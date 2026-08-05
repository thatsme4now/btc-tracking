package com.thatsme4now.depot.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
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
 * the "Jahresansicht" visualization, and backfill missing months live from
 * Kraken (EUR/USD) — see MonthlyPriceSeeder for the bundled CSV seed data
 * covering the initial setup.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonthlyPriceService {

    private static final String TICKER = "BTC";

    private final MonthlyPriceRepository monthlyPriceRepo;
    private final TransactionRepository  transactionRepo;
    private final KrakenService          krakenService;
    private final DepotService           depotService;

    /** Grobe jährliche USD→THB-Näherungskurse (Kraken hat kein BTC/THB-Paar) — dieselben
     *  Werte wie THB_PER_USD in HistoricalPriceSeeder, nur hier pro Monat statt pro Jahr
     *  angewendet. Bewusst ungenau (EIN Kurs für alle 12 Monate eines Jahres) — reicht für
     *  die informative Wertentwicklungs-Kurve, ersetzt aber keine echte monatliche FX-Quelle. */
    private static final Map<Integer, BigDecimal> THB_PER_USD_YEARLY = new java.util.LinkedHashMap<>();
    static {
        THB_PER_USD_YEARLY.put(2013, new BigDecimal("32.9"));
        THB_PER_USD_YEARLY.put(2014, new BigDecimal("32.9"));
        THB_PER_USD_YEARLY.put(2015, new BigDecimal("36.0"));
        THB_PER_USD_YEARLY.put(2016, new BigDecimal("35.8"));
        THB_PER_USD_YEARLY.put(2017, new BigDecimal("32.6"));
        THB_PER_USD_YEARLY.put(2018, new BigDecimal("32.6"));
        THB_PER_USD_YEARLY.put(2019, new BigDecimal("30.0"));
        THB_PER_USD_YEARLY.put(2020, new BigDecimal("30.0"));
        THB_PER_USD_YEARLY.put(2021, new BigDecimal("33.4"));
        THB_PER_USD_YEARLY.put(2022, new BigDecimal("34.6"));
        THB_PER_USD_YEARLY.put(2023, new BigDecimal("34.1"));
        THB_PER_USD_YEARLY.put(2024, new BigDecimal("34.0"));
        THB_PER_USD_YEARLY.put(2025, new BigDecimal("32.5"));
    }

    /** One row per month (earliest transaction month .. current month) for the manual price dialog. */
    public List<MonthlyPriceDTO> getMonthly(String currency) {
        String cur = (currency == null || currency.isBlank()) ? "EUR" : currency.toUpperCase();
        LocalDate now = LocalDate.now();
        YearMonth currentYm = YearMonth.from(now);

        YearMonth firstYm = transactionRepo.findFirstByOrderByDateAsc()
                .map(tx -> YearMonth.from(tx.getDate()))
                .orElse(currentYm);
        // Never show earlier than Kraken's own history start — no point offering to fill unreachable months.
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
     * Reine Kurs-Historie für den Bitcoin-Kurs-Chart der Gesamtansicht — ALLE
     * Monate, die in der monthly_price Tabelle für diese Währung vorhanden
     * sind, plus der laufende Monat mit dem aktuell hinterlegten Live-Kurs.
     * Bewusst NICHT auf den Zeitraum der ersten eigenen Transaktion begrenzt
     * (anders als getMonthly()/MonthlyOverviewService#getOverview) — dieser
     * Chart soll die komplette verfügbare historische Kurskurve zeigen, auch
     * für Jahre vor dem eigenen Einstieg. Eigene, unabhängige Abfrage statt
     * Wiederverwendung von getOverview()'s Bereichslogik.
     */
    public List<MonthlyPriceDTO> getPriceHistory(String currency) {
        String cur = (currency == null || currency.isBlank()) ? "EUR" : currency.toUpperCase();
        YearMonth currentYm = YearMonth.now();

        List<MonthlyPriceDTO> result = new ArrayList<>();
        for (MonthlyPrice mp : monthlyPriceRepo.findByTickerAndCurrencyOrderByYearAscMonthAsc(TICKER, cur)) {
            YearMonth ym = YearMonth.of(mp.getYear(), mp.getMonth());
            if (!ym.isBefore(currentYm)) continue; // laufender/zukünftiger Monat s.u. mit Live-Kurs
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
        monthlyPriceRepo.save(mp);

        MonthlyPriceDTO dto = new MonthlyPriceDTO();
        dto.setYear(year);
        dto.setMonth(month);
        dto.setCurrency(cur);
        dto.setPrice(mp.getPrice());
        return dto;
    }

    /**
     * Live backfill for all 3 supported currencies at once. EUR/USD come directly
     * from Kraken's public API (see KrakenService — no API key, no time-range
     * restriction, unlike CoinGecko's free tier). THB has no Kraken pair, so it's
     * derived from the USD history via a rough yearly FX rate (see
     * THB_PER_USD_YEARLY above). Only fills months that have no row yet — never
     * overwrites an existing value, whether it came from the seeder, an earlier
     * backfill, or a manual correction. The running (not yet elapsed) month is
     * always skipped.
     *
     * @return total number of newly inserted rows across all 3 currencies
     */
    @Transactional
    public int backfill() {
        YearMonth currentYm = YearMonth.now();
        int inserted = 0;

        Map<YearMonth, BigDecimal> usdHistory = Map.of();
        try {
            usdHistory = krakenService.loadMonthlyHistory("USD");
            inserted += storeMissing("USD", usdHistory, currentYm);
        } catch (Exception e) {
            log.error("Monthly backfill failed for USD (Kraken): {}", e.getMessage());
        }

        try {
            Map<YearMonth, BigDecimal> eurHistory = krakenService.loadMonthlyHistory("EUR");
            inserted += storeMissing("EUR", eurHistory, currentYm);
        } catch (Exception e) {
            log.error("Monthly backfill failed for EUR (Kraken): {}", e.getMessage());
        }

        if (!usdHistory.isEmpty()) {
            Map<YearMonth, BigDecimal> thbHistory = new java.util.LinkedHashMap<>();
            usdHistory.forEach((ym, usdPrice) ->
                    thbHistory.put(ym, usdPrice.multiply(thbRateForYear(ym.getYear())).setScale(2, RoundingMode.HALF_UP)));
            inserted += storeMissing("THB", thbHistory, currentYm);
        }

        if (inserted > 0) {
            log.info("Backfilled {} monthly price row(s).", inserted);
        }
        return inserted;
    }

    private int storeMissing(String currency, Map<YearMonth, BigDecimal> history, YearMonth currentYm) {
        int count = 0;
        for (Map.Entry<YearMonth, BigDecimal> entry : history.entrySet()) {
            YearMonth ym = entry.getKey();
            if (!ym.isBefore(currentYm)) continue; // running/future month → always live price
            if (monthlyPriceRepo.existsByTickerAndYearAndMonthAndCurrency(TICKER, ym.getYear(), ym.getMonthValue(), currency)) {
                continue; // already present — never overwrite
            }
            MonthlyPrice mp = new MonthlyPrice();
            mp.setTicker(TICKER);
            mp.setYear(ym.getYear());
            mp.setMonth(ym.getMonthValue());
            mp.setCurrency(currency);
            mp.setPrice(entry.getValue());
            monthlyPriceRepo.save(mp);
            count++;
        }
        return count;
    }

    private BigDecimal thbRateForYear(int year) {
        int clamped = Math.min(Math.max(year, 2013), 2025);
        return THB_PER_USD_YEARLY.getOrDefault(clamped, new BigDecimal("32.5"));
    }
}
