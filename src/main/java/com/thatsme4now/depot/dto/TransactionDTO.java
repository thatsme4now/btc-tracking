package com.thatsme4now.depot.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.thatsme4now.depot.entity.TransactionType;

import lombok.Data;

@Data
public class TransactionDTO {
    private Long id;
    private String transactionId;
    private Long positionId;
    private String positionLabel;
    private TransactionType type;
    private LocalDateTime date;
    private BigDecimal quantity;      // BTC
    private BigDecimal pricePerBtc;   // EUR, null for transfers
    private BigDecimal fees;          // EUR, optional
    private String feesCurrency;          // trading currency, e.g. EUR, USDT
    private BigDecimal quantityFiat;   // quantity * pricePerBtc + fees (calculated)
    private String currency;          // trading currency, e.g. EUR, USDT
    private BigDecimal exchangeRate;  // rate to EUR, default 1.0
    private String transferId;        // UUID, links TRANSFER_IN / TRANSFER_OUT pair
    private String comment;
}