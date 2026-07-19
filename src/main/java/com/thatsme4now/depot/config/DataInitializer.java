package com.thatsme4now.depot.config;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.thatsme4now.depot.entity.CurrentPrice;
import com.thatsme4now.depot.entity.Position;
import com.thatsme4now.depot.entity.PositionType;
import com.thatsme4now.depot.entity.Transaction;
import com.thatsme4now.depot.entity.TransactionType;
import com.thatsme4now.depot.repository.CurrentPriceRepository;
import com.thatsme4now.depot.repository.PositionRepository;
import com.thatsme4now.depot.repository.TransactionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Legt beim allerersten Start (keine current_price Einträge vorhanden)
 * Default-Preise, zwei Default-Positionen (Exchange + Wallet), eine
 * Beispiel-BUY-Transaktion und einen gepaarten Transfer vom Exchange
 * zur eigenen Wallet an.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer {

    private final CurrentPriceRepository currentPriceRepo;
    private final PositionRepository     positionRepo;
    private final TransactionRepository  transactionRepo;

    private static final String TICKER = "BTC";

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initDefaults() {
        if (currentPriceRepo.count() > 0) {
            return; // bereits initialisiert
        }

        log.info("No current_price entries found — seeding default data (first start).");

        LocalDate today = LocalDate.now();
        BigDecimal defaultEurPrice = new BigDecimal("55000");
        BigDecimal buyEurPrice = new BigDecimal("50000");

        saveDefaultPrice("EUR", defaultEurPrice, today);
        saveDefaultPrice("USD", new BigDecimal("65000"), today);
        saveDefaultPrice("THB", new BigDecimal("2100000"), today);

        Position exchange = createDefaultPosition("21 Bitcoin", PositionType.EXCHANGE);
        Position wallet    = createDefaultPosition("Bitbox02", PositionType.WALLET);

        LocalDateTime buyDate = LocalDateTime.now();
        BigDecimal buyQuantity = new BigDecimal("0.01000000");
        createExampleBuyTransaction(exchange, buyQuantity, buyEurPrice, buyDate);

        LocalDateTime transferDate = buyDate.plusDays(1);
        BigDecimal transferQuantity = new BigDecimal("0.00500000");
        BigDecimal transferFee      = new BigDecimal("0.00010000");
        createExampleTransfer(exchange, wallet, transferQuantity, transferFee, transferDate);
    }

    private void saveDefaultPrice(String currency, BigDecimal price, LocalDate date) {
        CurrentPrice cp = new CurrentPrice();
        cp.setTicker(TICKER);
        cp.setCurrency(currency);
        cp.setPrice(price);
        cp.setPriceDate(date);
        cp.setLoadedAt(LocalDateTime.now());
        currentPriceRepo.save(cp);
    }

    private Position createDefaultPosition(String label, PositionType type) {
        return positionRepo.findByLabel(label).orElseGet(() -> {
            Position p = new Position();
            p.setLabel(label);
            p.setType(type);
            return positionRepo.save(p);
        });
    }

    private void createExampleBuyTransaction(Position position, BigDecimal quantity,
                                              BigDecimal pricePerBtc, LocalDateTime date) {
        if (transactionRepo.count() > 0) {
            return;
        }

        Transaction tx = new Transaction();
        tx.setPosition(position);
        tx.setType(TransactionType.BUY);
        tx.setDate(date);
        tx.setQuantity(quantity);
        tx.setPricePerBtc(pricePerBtc);
        tx.setQuantityFiat(quantity.multiply(pricePerBtc));
        tx.setCurrency("EUR");
        tx.setExchangeRate(BigDecimal.ONE);
        tx.setFees(BigDecimal.ZERO);
        tx.setFeesCurrency("EUR");
        tx.setComment("Example transaction");
        tx.setTransactionId(UUID.randomUUID().toString());
        tx.setDuplicate(false);
        transactionRepo.save(tx);
    }

    private void createExampleTransfer(Position from, Position to, BigDecimal quantity,
                                        BigDecimal fee, LocalDateTime date) {
        String transferId = UUID.randomUUID().toString();

        Transaction out = new Transaction();
        out.setPosition(from);
        out.setType(TransactionType.TRANSFER_OUT);
        out.setDate(date);
        out.setQuantity(quantity);
        out.setCurrency("EUR");
        out.setExchangeRate(BigDecimal.ONE);
        out.setFees(fee);
        out.setFeesCurrency("BTC");
        out.setComment("Example transfer to own wallet");
        out.setTransferId(transferId);
        out.setTransactionId(UUID.randomUUID().toString());
        out.setDuplicate(false);
        transactionRepo.save(out);

        Transaction in = new Transaction();
        in.setPosition(to);
        in.setType(TransactionType.TRANSFER_IN);
        in.setDate(date);
        in.setQuantity(quantity.subtract(fee));
        in.setCurrency("EUR");
        in.setExchangeRate(BigDecimal.ONE);
        in.setComment("Example transfer to own wallet");
        in.setTransferId(transferId);
        in.setTransactionId(UUID.randomUUID().toString());
        in.setDuplicate(false);
        transactionRepo.save(in);
    }
}