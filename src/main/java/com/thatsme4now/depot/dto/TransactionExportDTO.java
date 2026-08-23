package com.thatsme4now.depot.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.thatsme4now.depot.entity.TransactionType;
import lombok.Data;

@Data
public class TransactionExportDTO {
    private Long id;
    private String transactionId;
    private String blockchainTxId;
    private Long positionId;
    private TransactionType type;
    private LocalDateTime date;
    private BigDecimal quantity;
    private BigDecimal quantityFiat;
    private BigDecimal pricePerBtc;
    private BigDecimal fees;
    private String feesCurrency;
    private String currency;
    private BigDecimal exchangeRate;
    private String transferId;
    private boolean duplicate;
    private String comment;
    private Long importHistoryId;
}