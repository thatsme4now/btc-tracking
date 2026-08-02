package com.thatsme4now.depot.dto;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public class ImportHistoryDTO {
    private Long id;
    private LocalDateTime importedAt;
    private String filename;
    private int totalRows;
    private int importedRows;
    private int duplicateRows;
    private int errorRows;

    /** Anzahl noch verknüpfter Transaktionen (import_history_id = id) —
     *  0 bei Imports von vor dieser Funktion. Für das Lösch-Bestätigungs-Modal. */
    private long linkedTransactionCount;
}
