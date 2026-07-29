package com.thatsme4now.depot.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class HistoricalPriceDTO {
    private int year;
    private String currency;
    /** Null if no reference price has been stored for this year/currency yet. */
    private BigDecimal price;
}
