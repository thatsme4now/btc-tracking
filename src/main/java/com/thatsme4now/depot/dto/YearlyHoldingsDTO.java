package com.thatsme4now.depot.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class YearlyHoldingsDTO {
    private int year;
    private boolean currentYear;

    /** Sum of BUY cost for this year, grouped by exchange/wallet label, in the requested display currency. */
    private Map<String, BigDecimal> buysByExchange = new LinkedHashMap<>();

    /** Sum of all BUY cost for this year (= sum of buysByExchange values). */
    private BigDecimal totalBuys = BigDecimal.ZERO;

    /** Sum of SELL proceeds for this year, in the requested display currency. */
    private BigDecimal totalSells = BigDecimal.ZERO;

    /** Realized gain/loss for this year (sale proceeds minus weighted-average cost basis of the sold BTC). */
    private BigDecimal realizedPnl = BigDecimal.ZERO;

    /**
     * Unrealized gain/loss as of 31.12. of this year (portfolio-wide holdings valued at that
     * year's BTC reference price, minus the weighted-average cost basis at that point) — for the
     * current, still-running year this uses the live price instead of a 31.12. price. Null if no
     * reference price is available yet for a past year (see HistoricalPriceService) — the frontend
     * shows a gap and the user can fill it in via the reference-price table.
     */
    private BigDecimal unrealizedPnl;

    /** Total BTC held across the whole portfolio as of 31.12. of this year (or now, for the current year). */
    private BigDecimal btcBalance = BigDecimal.ZERO;
}
