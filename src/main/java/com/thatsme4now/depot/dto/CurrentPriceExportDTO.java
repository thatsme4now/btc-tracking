package com.thatsme4now.depot.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class CurrentPriceExportDTO {
    private String ticker;
    private String currency;
    private BigDecimal price;
    private LocalDate priceDate;
    private LocalDateTime loadedAt;
}
