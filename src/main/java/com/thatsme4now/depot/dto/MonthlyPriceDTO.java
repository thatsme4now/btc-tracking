package com.thatsme4now.depot.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class MonthlyPriceDTO {
    private int year;
    private int month;
    private String currency;
    /** Null if no price has been stored for this month/currency yet (and it isn't the live current month). */
    private BigDecimal price;
    /** True for the currently running (not yet elapsed) month — always uses the live current price, not editable here. */
    private boolean current;
}
