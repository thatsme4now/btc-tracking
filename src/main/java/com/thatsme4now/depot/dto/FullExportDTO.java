package com.thatsme4now.depot.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

@Data
public class FullExportDTO {
    private String format = "btc-tracking-full-export";
    private int version = 1;
    private LocalDateTime exportedAt;
    private List<PositionExportDTO> positions;
    private List<TransactionExportDTO> transactions;
}