package com.thatsme4now.depot.dto;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class MonthlyPriceExportDTO {
    private Long id;
    private String ticker;
    private Integer year;
    private Integer month;
    private String currency;
    private BigDecimal price;
}
