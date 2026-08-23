package com.thatsme4now.depot;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.thatsme4now.depot.entity.CurrentPrice;
import com.thatsme4now.depot.entity.Position;
import com.thatsme4now.depot.entity.PositionType;
import com.thatsme4now.depot.entity.Transaction;
import com.thatsme4now.depot.entity.TransactionType;

/** Small factory helpers for building test entities without persisting them. */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static Position position(String label, PositionType type) {
        Position p = new Position();
        p.setLabel(label);
        p.setType(type);
        return p;
    }

    public static CurrentPrice currentPrice(String currency, BigDecimal price) {
        CurrentPrice cp = new CurrentPrice();
        cp.setTicker("BTC");
        cp.setCurrency(currency);
        cp.setPrice(price);
        cp.setPriceDate(LocalDateTime.now().toLocalDate());
        cp.setLoadedAt(LocalDateTime.now());
        return cp;
    }

    public static Transaction buy(Position position, LocalDateTime date, BigDecimal quantity,
                                   BigDecimal pricePerBtc, String currency) {
        Transaction tx = new Transaction();
        tx.setPosition(position);
        tx.setType(TransactionType.BUY);
        tx.setDate(date);
        tx.setQuantity(quantity);
        tx.setPricePerBtc(pricePerBtc);
        tx.setQuantityFiat(quantity.multiply(pricePerBtc));
        tx.setCurrency(currency);
        tx.setExchangeRate(BigDecimal.ONE);
        tx.setTransactionId(UUID.randomUUID().toString());
        return tx;
    }

    public static Transaction sell(Position position, LocalDateTime date, BigDecimal quantity,
                                    BigDecimal pricePerBtc, String currency) {
        Transaction tx = new Transaction();
        tx.setPosition(position);
        tx.setType(TransactionType.SELL);
        tx.setDate(date);
        tx.setQuantity(quantity);
        tx.setPricePerBtc(pricePerBtc);
        tx.setQuantityFiat(quantity.multiply(pricePerBtc));
        tx.setCurrency(currency);
        tx.setExchangeRate(BigDecimal.ONE);
        tx.setTransactionId(UUID.randomUUID().toString());
        return tx;
    }

    public static Transaction transferOut(Position from, LocalDateTime date, BigDecimal quantity, String transferId) {
        Transaction tx = new Transaction();
        tx.setPosition(from);
        tx.setType(TransactionType.TRANSFER_OUT);
        tx.setDate(date);
        tx.setQuantity(quantity);
        tx.setCurrency("EUR");
        tx.setExchangeRate(BigDecimal.ONE);
        tx.setTransferId(transferId);
        tx.setTransactionId(UUID.randomUUID().toString());
        return tx;
    }

    public static Transaction transferIn(Position to, LocalDateTime date, BigDecimal quantity, String transferId) {
        Transaction tx = new Transaction();
        tx.setPosition(to);
        tx.setType(TransactionType.TRANSFER_IN);
        tx.setDate(date);
        tx.setQuantity(quantity);
        tx.setCurrency("EUR");
        tx.setExchangeRate(BigDecimal.ONE);
        tx.setTransferId(transferId);
        tx.setTransactionId(UUID.randomUUID().toString());
        return tx;
    }
}
