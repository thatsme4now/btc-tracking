package com.thatsme4now.depot.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

@Data
public class FullExportDTO {
    private String format = "btc-tracking-full-export";
    private int version = 2;
    private LocalDateTime exportedAt;
    private List<PositionExportDTO> positions;
    private List<TransactionExportDTO> transactions;

    // Ab Version 2 (Backup deckt mehr als nur Positionen/Transaktionen ab).
    // Bewusst als eigenständige, nullable Listen: fehlt ein Feld beim Import
    // (z.B. Backup von vor diesem Update), bleibt die jeweilige Zieltabelle
    // unberührt statt geleert zu werden — siehe DataExportService#importFull.
    private List<ImportHistoryExportDTO> importHistory;
    private List<PriceHistoryExportDTO> priceHistory;
    private List<CurrentPriceExportDTO> currentPrices;
    private List<HistoricalPriceExportDTO> historicalPrices;
    private List<MonthlyPriceExportDTO> monthlyPrices;
}