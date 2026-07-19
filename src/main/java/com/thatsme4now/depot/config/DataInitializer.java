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
 * Default-Preise, eine Default-Position und eine Beispiel-Transaktion an,
 * damit die Anwendung nicht komplett leer startet.
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
        BigDecimal defaultEurPrice = new BigDecimal("50000");

        saveDefaultPrice("EUR", defaultEurPrice, today);
        saveDefaultPrice("USD", new BigDecimal("60000"), today);
        saveDefaultPrice("THB", new BigDecimal("2000000"), today);

        Position position = createDefaultPosition();
        createExampleTransaction(position, defaultEurPrice);
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

    private Position createDefaultPosition() {
        if (positionRepo.count() > 0) {
            return positionRepo.findAll().get(0);
        }
        Position p = new Position();
        p.setLabel("21 Bitcoin");
        p.setType(PositionType.EXCHANGE);
        return positionRepo.save(p);
    }

    private void createExampleTransaction(Position position, BigDecimal pricePerBtc) {
        if (transactionRepo.count() > 0) {
            return;
        }
        BigDecimal quantity = new BigDecimal("0.01000000");

        Transaction tx = new Transaction();
        tx.setPosition(position);
        tx.setType(TransactionType.BUY);
        tx.setDate(LocalDateTime.now());
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
}