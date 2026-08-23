package com.thatsme4now.depot.dto;

import java.time.LocalDateTime;
import lombok.Data;

/** Backup section for {@link com.thatsme4now.depot.entity.PositionAddress} — see FullExportDTO#positionAddresses. */
@Data
public class PositionAddressExportDTO {
    private Long id;
    private Long positionId;
    private String address;
    private String label;
    private String lastFetchJson;
    private Long lastFetchBalanceSats;
    private String lastFetchTxsJson;
    private LocalDateTime lastFetchAt;
}
