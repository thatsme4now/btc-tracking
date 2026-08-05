package com.thatsme4now.depot.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;

/**
 * Year-end (31.12.) reference price for BTC, per supported display currency.
 *
 * Used by the "Bestandsansicht" (yearly holdings) visualization for past
 * years, since a fixed point-in-time reference price is enough there —
 * unlike the current year, which always uses the live {@link CurrentPrice}.
 *
 * Values are approximate historical closing prices sourced from public
 * price aggregators at seed time (see HistoricalPriceSeeder), not exact
 * tick-level data. Rows can be corrected manually in the DB if needed —
 * this is a small reference table, not transactional data.
 */
@Data
@Entity
@Table(name = "historical_price",
       uniqueConstraints = @UniqueConstraint(columnNames = {"ticker", "price_year", "currency"}))
public class HistoricalPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String ticker = "BTC";

    // Column named price_year (not "year") — YEAR is a reserved word/function
    // name in MySQL-compatible SQL, same reasoning as the backtick-quoted
    // `position`/`transaction` tables elsewhere in schema-h2.sql.
    @Column(name = "price_year", nullable = false)
    private Integer year;

    @Column(nullable = false, length = 10)
    private String currency;

    /** BTC price on 31.12. of {@link #year}, in {@link #currency}. */
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal price;
}
