package com.thatsme4now.depot.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.thatsme4now.depot.entity.TransactionType;

import lombok.Data;

@Data
public class TransactionDTO {
    private Long id;
    private String transactionId;
    private String blockchainTxId;    // real on-chain BTC TXID, separate from transactionId (import dedup key) — TRANSFER_IN/TRANSFER_OUT only
    private Long positionId;
    private String positionLabel;
    private String positionType;      // EXCHANGE, WALLET, ... (Position.type)
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
    private boolean duplicate;
    private String comment;
}