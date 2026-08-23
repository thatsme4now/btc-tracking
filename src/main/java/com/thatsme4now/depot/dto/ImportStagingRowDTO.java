package com.thatsme4now.depot.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.thatsme4now.depot.entity.TransactionType;

import lombok.Data;

@Data
public class ImportStagingRowDTO {
    private Long id;
    private Integer rowIndex;
    private String rawTyp;
    private TransactionType type;
    private String positionLabel;
    private String dateRaw;
    private LocalDateTime dateParsed;
    private BigDecimal quantity;
    private BigDecimal quantityFiat;
    private BigDecimal pricePerBtc;
    private String currency;
    private BigDecimal exchangeRate;
    private BigDecimal fees;
    private String feesCurrency;
    private String comment;
    private String transactionId;
    private String blockchainTxId;
    private String transferId;
    private boolean duplicate;
    private boolean fxWarning;
    private boolean hasError;
    private String errorReason;
}
