package com.thatsme4now.depot.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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

    private final HistoricalPriceRepository historicalPriceRepo;
    private final TransactionRepository     transactionRepo;

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
}
