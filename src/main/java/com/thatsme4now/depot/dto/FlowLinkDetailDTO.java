package com.thatsme4now.depot.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class FlowLinkDetailDTO {
    private LocalDateTime date;
    private BigDecimal quantity;
    private BigDecimal originalQuantity;
    private String transactionId;

    /** Vollständige Transaktionsdaten der primären Buchung (z.B. TRANSFER_OUT-Seite bei Transfers). */
    private TransactionDTO transaction;

    /** Bei Transfers: die gepaarte Gegenbuchung (TRANSFER_IN). Null bei BUY/SELL/EXTERNAL. */
    private TransactionDTO pairedTransaction;
}