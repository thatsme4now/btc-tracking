package com.thatsme4now.depot.dto;
 
import com.thatsme4now.depot.entity.PositionType;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
 
@Data
public class PositionDTO {
    private Long id;
    private String label;
    private PositionType type;
 
    // calculated from transactions
    private BigDecimal quantity;         // BTC (BUY + TRANSFER_IN - SELL - TRANSFER_OUT)
    private BigDecimal quantityInSats;   // quantity * 100_000_000
    private BigDecimal avgPurchasePrice; // weighted avg of BUY transactions in EUR
    private BigDecimal invested;         // quantity * avgPurchasePrice in EUR
    private BigDecimal realized;         

    // from current_price
    private BigDecimal currentPrice;
    private LocalDate  priceDate;
 
    // calculated
    private BigDecimal totalValue;      // quantity * currentPrice in EUR
    private BigDecimal gainLoss;        // totalValue - invested in EUR
    private BigDecimal performancePct;  // gainLoss / invested * 100

    // on-chain balance (from cached mempool address lookups — see position_address), for the
    // "On-Chain" table column. hasAddresses distinguishes "no addresses configured" (column shows
    // nothing) from "addresses configured but onchainBalanceSats still null" (nothing fetched yet).
    private boolean hasAddresses;
    private Long       onchainBalanceSats;      // null: no addresses, or none fetched yet
    private BigDecimal onchainBalanceBtc;       // same value as onchainBalanceSats, pre-divided for display
    private boolean    onchainBalancePartial;   // true: not every address has been fetched at least once
    private boolean    onchainBalanceDiffers;   // true: onchainBalanceSats != quantityInSats (shown even while partial)
}
 