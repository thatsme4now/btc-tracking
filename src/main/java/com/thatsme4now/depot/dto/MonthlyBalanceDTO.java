package com.thatsme4now.depot.dto;

import lombok.Data;
import java.math.BigDecimal;

/** One point on the "Jahresansicht" Bestand/Wertentwicklung line chart. */
@Data
public class MonthlyBalanceDTO {
    private int year;
    private int month;
    private BigDecimal btcBalance;
    /** btcBalance × month-end price, in the requested display currency. Null if no price is known yet for this month. */
    private BigDecimal value;
    /** True for the currently running (not yet elapsed) month — value uses the live current price. */
    private boolean current;
}
