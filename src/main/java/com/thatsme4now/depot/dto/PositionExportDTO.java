package com.thatsme4now.depot.dto;

import com.thatsme4now.depot.entity.PositionType;
import lombok.Data;

@Data
public class PositionExportDTO {
    private Long id;
    private String label;
    private PositionType type;
}