package com.thatsme4now.depot.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Monthly (Ultimo, i.e. last day of the month) reference price for BTC, per
 * supported display currency.
 *
 * Used by the "Jahresansicht" visualization for the Bestand/Wertentwicklung
 * line chart. The currently running (not yet elapsed) month always uses the
 * live {@link CurrentPrice} instead — see MonthlyPriceService.
 *
 * Rows come from either the bundled monthly-btc-prices.csv seed resource
 * (see MonthlyPriceSeeder) or manual correction via the UI — once a row
 * exists for a given (ticker, year, month, currency) it is never silently
 * overwritten by the seeder again.
 */
@Data
@Entity
@Table(name = "monthly_price",
       uniqueConstraints = @UniqueConstraint(columnNames = {"ticker", "price_year", "price_month", "currency"}))
public class MonthlyPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String ticker = "BTC";

    // price_year/price_month (not "year"/"month") — reserved words in some
    // SQL dialects, same reasoning as historical_price.price_year.
    @Column(name = "price_year", nullable = false)
    private Integer year;

    @Column(name = "price_month", nullable = false)
    private Integer month;

    @Column(nullable = false, length = 10)
    private String currency;

    /** BTC price on the last day of {@link #month}/{@link #year}, in {@link #currency}. */
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal price;

    /** "MANUAL" (Standard: CSV-Seed oder manuelle Eingabe) oder "MEMPOOL" (per Bulk-Fill von der mempool-API geholt). */
    @Column(length = 20)
    private String source = "MANUAL";

    @Column(name = "loaded_at")
    private LocalDateTime loadedAt;
}
