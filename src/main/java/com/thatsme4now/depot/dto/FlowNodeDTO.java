package com.thatsme4now.depot.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class FlowNodeDTO {
    private String id;
    private String kind;          // POSITION, BUY, SELL, EXTERNAL_IN, EXTERNAL_OUT
    private Long positionId;
    private String positionLabel;
    private String positionType;  // EXCHANGE, WALLET, ...
    private BigDecimal currentBalance; // nur bei kind=POSITION gesetzt
}