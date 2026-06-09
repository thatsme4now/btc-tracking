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
}
 