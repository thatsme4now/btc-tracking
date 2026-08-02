package com.thatsme4now.depot.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class ImportHistoryExportDTO {
    private Long id;
    private LocalDateTime importedAt;
    private String filename;
    private int totalRows;
    private int importedRows;
    private int duplicateRows;
    private int errorRows;
}
