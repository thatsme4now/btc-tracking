package com.thatsme4now.depot.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class FlowLinkDTO {
    private String id;
    private String source;
    private String target;
    private BigDecimal value;   // Summe BTC im Monat
    private String month;       // yyyy-MM
    private List<FlowLinkDetailDTO> details;
}