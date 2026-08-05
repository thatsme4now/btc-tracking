package com.thatsme4now.depot.dto;

import lombok.Data;
import java.math.BigDecimal;

/**
 * Portfolio-weite Kennzahlen (Kennzahlen-Kachel) — einmal berechnet in
 * HoldingsYearlyService.computePortfolioMetrics(), genutzt von der
 * Bestandsansicht (per REST, siehe GET /api/btc-tracking/metrics), damit
 * die Werte nicht an zwei Stellen unabhängig berechnet werden
 * (Drift-Risiko, siehe Kommentar in HoldingsYearlyService.computePortfolioMetrics).
 */
@Data
public class PortfolioMetricsDTO {
    private BigDecimal totalBtc;
    private BigDecimal totalSats;
    private BigDecimal totalValue;
    private BigDecimal invested;
    private BigDecimal realized;
    private BigDecimal gainLoss;
    private BigDecimal performancePct;
    private long        transactionCount;
}
