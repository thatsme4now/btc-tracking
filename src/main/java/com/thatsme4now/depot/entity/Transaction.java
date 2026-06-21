package com.thatsme4now.depot.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "transaction",
       indexes = {
           @Index(name = "idx_tx_position", columnList = "position_id"),
           @Index(name = "idx_tx_transfer", columnList = "transfer_id")
       })
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "transaction_id", nullable = true, length = 36)
    private String transactionId;

    //@ManyToOne(fetch = FetchType.LAZY, optional = false)
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "position_id", nullable = false)
    private Position position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionType type;

    @Column(nullable = false)
    private LocalDateTime date;

    /** Amount in BTC */
    @Column(nullable = false, precision = 18, scale = 8)
    private BigDecimal quantity;
    
    /** Amount in Fiat */
    @Column(name = "quantity_fiat", nullable = true, precision = 14, scale = 2)
    private BigDecimal quantityFiat;
    
    /**
     * Currency used in this transaction (e.g. EUR, USDT).
     * Only relevant for BUY / SELL. Defaults to EUR.
     */
    @Column(length = 10)
    private String currency = "EUR";

    /**
     * Exchange rate of currency to EUR at time of transaction.
     * Defaults to 1.0 (= currency is EUR). Adjust manually for non-EUR pairs.
     */
    @Column(name = "exchange_rate", precision = 14, scale = 6)
    private BigDecimal exchangeRate = BigDecimal.ONE;

    /**
     * Price per BTC in EUR at time of transaction.
     * Null for TRANSFER_IN / TRANSFER_OUT (no price involved).
     */
    @Column(name = "price_per_btc", precision = 14, scale = 2)
    private BigDecimal pricePerBtc;

    /**
     * Transaction or network fees in EUR.
     * Optional for all types.
     */
    @Column(precision = 18, scale = 8)
    private BigDecimal fees;
    
    /**
     * Currency used in this transaction for fee(e.g. EUR, USDT).
     */
    @Column(name = "fees_currency", length = 10)
    private String feesCurrency = "EUR";

    @Column(name = "comment", length = 255)
    private String comment;
    
    /**
     * Links TRANSFER_IN and TRANSFER_OUT that belong together.
     * UUID as String, nullable for BUY/SELL.
     */
    @Column(name = "transfer_id", length = 36)
    private String transferId;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}