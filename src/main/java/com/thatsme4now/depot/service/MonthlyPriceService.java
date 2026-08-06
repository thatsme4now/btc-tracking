package com.thatsme4now.depot.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

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

    private final MonthlyPriceRepository monthlyPriceRepo;
    private final TransactionRepository  transactionRepo;
    private final DepotService           depotService;

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

}
