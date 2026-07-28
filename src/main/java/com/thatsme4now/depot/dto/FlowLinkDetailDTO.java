package com.thatsme4now.depot.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class FlowLinkDetailDTO {
    private LocalDateTime date;
    private BigDecimal quantity;     
    private BigDecimal originalQuantity;
    private String transactionId;
}