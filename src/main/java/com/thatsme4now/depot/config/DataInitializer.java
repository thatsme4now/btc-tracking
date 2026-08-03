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
import com.thatsme4now.depot.entity.ImportHistory;
import com.thatsme4now.depot.entity.Position;
import com.thatsme4now.depot.entity.PositionType;
import com.thatsme4now.depot.entity.Transaction;
import com.thatsme4now.depot.entity.TransactionType;
import com.thatsme4now.depot.repository.CurrentPriceRepository;
import com.thatsme4now.depot.repository.ImportHistoryRepository;
import com.thatsme4now.depot.repository.PositionRepository;
import com.thatsme4now.depot.repository.TransactionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Legt beim allerersten Start (keine current_price Einträge vorhanden)
 * Default-Preise, drei Default-Positionen (2x Exchange + 1x Wallet) und
 * eine kleine Beispiel-Historie an: zwei BUYs auf unterschiedlichen Börsen
 * (eine EUR-, eine USD-Transaktion mit Wechselkurs, um die Fremdwährungs-
 * Anzeige zu demonstrieren), jeweils gefolgt von einem gepaarten Transfer
 * in die eigene Wallet, plus ein zusätzlicher "solo" Eingang ohne
 * Gegenbuchung (zeigt die Solo-Transfer-Markierung). Alle Daten sind
 * relativ zum Start-Zeitpunkt berechnet, damit die Demo unabhängig vom
 * tatsächlichen Installationsdatum immer plausibel aussieht.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer {

    private final CurrentPriceRepository currentPriceRepo;
    private final PositionRepository     positionRepo;
    private final TransactionRepository  transactionRepo;
    private final ImportHistoryRepository importHistoryRepo;

    private static final String TICKER = "BTC";

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initDefaults() {
        if (currentPriceRepo.count() > 0) {
            return; // bereits initialisiert
        }

        log.info("No current_price entries found — seeding default data (first start).");

        LocalDate today = LocalDate.now();
        saveDefaultPrice("EUR", new BigDecimal("55000"), today);
        saveDefaultPrice("USD", new BigDecimal("65000"), today);
        saveDefaultPrice("THB", new BigDecimal("2100000"), today);

        // Reihenfolge der Anlage bestimmt die Standard-Sortierung in der Wallets-
        // Kachel (findAll() ohne expliziten Sort) — Bitbox02, Binance, 21 Bitcoin.
        Position bitbox02   = createDefaultPosition("Bitbox02", PositionType.WALLET);
        Position binance    = createDefaultPosition("Binance", PositionType.EXCHANGE);
        Position exchange21 = createDefaultPosition("21 Bitcoin", PositionType.EXCHANGE);

        // Alle Beispiel-Transaktionen hängen an einem einzigen Import-Historie-
        // Eintrag ("example-import.csv", nur DB-Metadaten — keine echte Datei),
        // damit der Nutzer die komplette Demo-Historie mit einem Klick über den
        // normalen "Eintrag + Transaktionen löschen"-Dialog entfernen kann.
        // Zähler 1:1 pro tatsächlich angelegter Transaktion (7), da es keine
        // reale Datei gibt, mit der sich eine abweichende Rohzeilenzahl
        // rechtfertigen ließe.
        ImportHistory exampleImport = new ImportHistory();
        exampleImport.setFilename("example-import.csv");
        exampleImport.setTotalRows(7);
        exampleImport.setImportedRows(7);
        exampleImport.setDuplicateRows(0);
        exampleImport.setErrorRows(0);
        importHistoryRepo.save(exampleImport);
        Long exampleImportId = exampleImport.getId();

        LocalDateTime now = LocalDateTime.now();

        // ── Vor 2 Monaten: Kauf auf Binance in USD (Fremdwährung + Wechselkurs
        //    0.85), danach Transfer der Hälfte in die eigene Wallet.
        LocalDateTime binanceBuyDate = now.minusMonths(2).withDayOfMonth(1)
                .withHour(16).withMinute(0).withSecond(0).withNano(0);
        createBuyTransaction(binance, binanceBuyDate,
                new BigDecimal("0.01000000"), new BigDecimal("60000.00"), new BigDecimal("600.00"),
                "USD", new BigDecimal("0.85"), new BigDecimal("1.00"), "USD",
                "Example purchase", exampleImportId);
        createPairedTransfer(binance, bitbox02, binanceBuyDate.plusDays(1),
                new BigDecimal("0.00500000"), new BigDecimal("0.00010000"), exampleImportId);

        // ── Vor 1 Monat: Kauf auf 21 Bitcoin in EUR, danach ebenfalls Transfer
        //    der Hälfte in die eigene Wallet.
        LocalDateTime exchange21BuyDate = now.minusMonths(1).withDayOfMonth(1)
                .withHour(16).withMinute(0).withSecond(0).withNano(0);
        createBuyTransaction(exchange21, exchange21BuyDate,
                new BigDecimal("0.01000000"), new BigDecimal("52000.00"), new BigDecimal("520.00"),
                "EUR", BigDecimal.ONE, BigDecimal.ZERO, "EUR",
                "Example purchase", exampleImportId);
        createPairedTransfer(exchange21, bitbox02, exchange21BuyDate.plusDays(1),
                new BigDecimal("0.00500000"), new BigDecimal("0.00010000"), exampleImportId);

        // ── Vor 2 Wochen: zusätzlicher Eingang auf die Wallet ohne Gegenbuchung
        //    (demonstriert die Solo-Transfer-Markierung).
        LocalDateTime soloTransferDate = now.minusDays(14)
                .withHour(18).withMinute(0).withSecond(0).withNano(0);
        createSoloIncomingTransfer(bitbox02, soloTransferDate, new BigDecimal("0.02100000"),
                "1st Place Reward Kickstr :-)", exampleImportId);
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

    private void createBuyTransaction(Position position, LocalDateTime date,
                                       BigDecimal quantity, BigDecimal pricePerBtc, BigDecimal quantityFiat,
                                       String currency, BigDecimal exchangeRate,
                                       BigDecimal fees, String feesCurrency, String comment,
                                       Long importHistoryId) {
        Transaction tx = new Transaction();
        tx.setPosition(position);
        tx.setType(TransactionType.BUY);
        tx.setDate(date);
        tx.setQuantity(quantity);
        tx.setPricePerBtc(pricePerBtc);
        tx.setQuantityFiat(quantityFiat);
        tx.setCurrency(currency);
        tx.setExchangeRate(exchangeRate);
        tx.setFees(fees);
        tx.setFeesCurrency(feesCurrency);
        tx.setComment(comment);
        tx.setTransactionId(UUID.randomUUID().toString());
        tx.setDuplicate(false);
        tx.setImportHistoryId(importHistoryId);
        transactionRepo.save(tx);
    }

    /** TRANSFER_OUT auf {@code from} + gepaarter TRANSFER_IN auf {@code to}
     *  (gemeinsame transferId), Ziel-Menge abzüglich Netzwerkgebühr. */
    private void createPairedTransfer(Position from, Position to, LocalDateTime date,
                                       BigDecimal quantity, BigDecimal fee, Long importHistoryId) {
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
        out.setImportHistoryId(importHistoryId);
        transactionRepo.save(out);

        Transaction in = new Transaction();
        in.setPosition(to);
        in.setType(TransactionType.TRANSFER_IN);
        in.setDate(date.plusMinutes(5));
        in.setQuantity(quantity.subtract(fee));
        in.setCurrency("EUR");
        in.setExchangeRate(BigDecimal.ONE);
        in.setComment("Example transfer to own wallet");
        in.setTransferId(transferId);
        in.setTransactionId(UUID.randomUUID().toString());
        in.setDuplicate(false);
        in.setImportHistoryId(importHistoryId);
        transactionRepo.save(in);
    }

    /** TRANSFER_IN ohne Gegenbuchung (eigene transferId) — z. B. Einzahlung von
     *  einer externen Quelle, die nicht Teil des Depots ist. */
    private void createSoloIncomingTransfer(Position to, LocalDateTime date, BigDecimal quantity, String comment,
                                             Long importHistoryId) {
        Transaction in = new Transaction();
        in.setPosition(to);
        in.setType(TransactionType.TRANSFER_IN);
        in.setDate(date);
        in.setQuantity(quantity);
        in.setCurrency("EUR");
        in.setExchangeRate(BigDecimal.ONE);
        in.setComment(comment);
        in.setTransferId(UUID.randomUUID().toString());
        in.setTransactionId(UUID.randomUUID().toString());
        in.setDuplicate(false);
        in.setImportHistoryId(importHistoryId);
        transactionRepo.save(in);
    }
}
