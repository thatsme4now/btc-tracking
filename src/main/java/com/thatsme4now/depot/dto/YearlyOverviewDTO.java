package com.thatsme4now.depot.dto;

import lombok.Data;
import java.util.List;

/** Response for GET /api/btc-tracking/yearly-overview. */
@Data
public class YearlyOverviewDTO {
    /** Selected year, or null for the "Gesamtansicht" (full history). */
    private Integer year;
    /** All years that have at least one transaction — used to populate the year dropdown. */
    private List<Integer> availableYears;
    /** Monthly Bestand/Wertentwicklung series (see MonthlyBalanceDTO). */
    private List<MonthlyBalanceDTO> series;
}
