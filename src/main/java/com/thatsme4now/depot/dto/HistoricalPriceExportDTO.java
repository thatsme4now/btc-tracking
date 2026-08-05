package com.thatsme4now.depot.dto;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class HistoricalPriceExportDTO {
    private Long id;
    private String ticker;
    private Integer year;
    private String currency;
    private BigDecimal price;
}
