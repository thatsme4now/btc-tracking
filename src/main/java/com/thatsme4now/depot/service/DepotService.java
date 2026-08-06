package com.thatsme4now.depot.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.thatsme4now.depot.dto.PositionDTO;
import com.thatsme4now.depot.dto.TransactionDTO;
import com.thatsme4now.depot.entity.AppSettings;
import com.thatsme4now.depot.entity.CurrentPrice;
import com.thatsme4now.depot.entity.Position;
import com.thatsme4now.depot.entity.PriceHistory;
import com.thatsme4now.depot.entity.Transaction;
import com.thatsme4now.depot.entity.TransactionType;
import com.thatsme4now.depot.repository.AppSettingsRepository;
import com.thatsme4now.depot.repository.CurrentPriceRepository;
import com.thatsme4now.depot.repository.PositionRepository;
import com.thatsme4now.depot.repository.PriceHistoryRepository;
import com.thatsme4now.depot.repository.TransactionRepository;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DepotService {

    private final PositionRepository     positionRepo;
    private final TransactionRepository  transactionRepo;
    private final CurrentPriceRepository currentPriceRepo;
    private final PriceHistoryRepository priceHistoryRepo;
    private final AppSettingsRepository  appSettingsRepo;

    private static final String     TICKER = "BTC";
    private static final BigDecimal SATS   = BigDecimal.valueOf(100_000_000);

    // ── Positions ─────────────────────────────────────────

    public List<PositionDTO> getAllPositions(String currency) {
        String cur = normalizeCurrency(currency);
        CurrentPrice cp = currentPriceRepo.findByTickerAndCurrency(TICKER, cur).orElse(null);
        return positionRepo.findAll().stream()
                .map(p -> toDTO(p, cp))
                .collect(Collectors.toList());
    }

    public Optional<Position> getPosition(Long id) {
        return positionRepo.findById(id);
    }

    public Position save(Position position) {
        return positionRepo.save(position);
    }

    public void delete(Long id) {
        positionRepo.deleteById(id);
    }

    public void delete() {
        positionRepo.deleteAll();
    }

    // ── Current Price ─────────────────────────────────────

    public Optional<CurrentPrice> getCurrentPrice(String currency) {
        return currentPriceRepo.findByTickerAndCurrency(TICKER, normalizeCurrency(currency));
    }

    public CurrentPrice saveCurrentPrice(CurrentPrice cp) {
        return currentPriceRepo.save(cp);
    }

    // ── App Settings (Singleton-Zeile) ────────────────────

    public AppSettings getAppSettings() {
        return appSettingsRepo.findById(1L).orElseGet(() -> {
            AppSettings s = new AppSettings();
            s.setId(1L);
            return appSettingsRepo.save(s);
        });
    }

    public AppSettings saveAppSettings(AppSettings settings) {
        return appSettingsRepo.save(settings);
    }

    // ── Transactions ──────────────────────────────────────

    public long getTransactionCount() {
        return transactionRepo.count();
    }

    public long getTransactionCount(Long positionId) {
        return transactionRepo.countByPositionId(positionId);
    }

    public List<TransactionDTO> getAllTransactions() {
        return transactionRepo.findAllByOrderByDateDesc().stream()
                .map(this::toTransactionDTO)
                .collect(Collectors.toList());
    }

    public List<TransactionDTO> getTransactions(Long positionId) {
        return transactionRepo.findByPositionIdOrderByDateDesc(positionId).stream()
                .map(this::toTransactionDTO)
                .collect(Collectors.toList());
    }

    public Transaction saveTransaction(Transaction tx) {
        return transactionRepo.save(tx);
    }

    public void deleteTransaction(Long id) {
        transactionRepo.deleteById(id);
    }

    public void deleteTransaction() {
        transactionRepo.deleteAll();
    }

    public Optional<Transaction> getTransaction(Long id) {
        return transactionRepo.findById(id);
    }

    // ── Price History ─────────────────────────────────────

    public List<PriceHistory> getHistory() {
        return priceHistoryRepo.findByTickerOrderByDateAsc(TICKER);
    }

    // ── Helpers ───────────────────────────────────────────

    private String normalizeCurrency(String currency) {
        return (currency == null || currency.isBlank()) ? "EUR" : currency.toUpperCase();
    }

    // ── DTO Mapping ───────────────────────────────────────

    private PositionDTO toDTO(Position p, CurrentPrice cp) {
        List<Transaction> txs = transactionRepo.findByPositionIdOrderByDateAsc(p.getId());

        BigDecimal quantity = txs.stream()
                .map(tx -> switch (tx.getType()) {
                    case BUY, TRANSFER_IN, DEPOSIT   -> tx.getQuantity();
                    case SELL, TRANSFER_OUT, WITHDRAW -> tx.getQuantity().negate();
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalBuyQty = txs.stream()
                .filter(tx -> tx.getType() == TransactionType.BUY)
                .map(Transaction::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Kaufgebühren zählen mit zur Kostenbasis (Fund 4) — konsistent zur G/V-Spalte der
        // Haupttabelle und zum "Gewinn/Verlust je Kauf"-Chart (dort: quantityFiat + fees).
        BigDecimal totalBuyCost = txs.stream()
                .filter(tx -> tx.getType() == TransactionType.BUY && tx.getPricePerBtc() != null)
                .map(tx -> {
                    BigDecimal rate = tx.getExchangeRate() != null ? tx.getExchangeRate() : BigDecimal.ONE;
                    BigDecimal fees = tx.getFees() != null ? tx.getFees() : BigDecimal.ZERO;
                    BigDecimal cost = tx.getQuantity().multiply(tx.getPricePerBtc()).add(fees);
                    if (cp != null && cp.getCurrency().equals(tx.getCurrency())) {
                    	return cost;
                    } else {
                    	return cost.multiply(rate);
                    }
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal avgPurchasePrice = totalBuyQty.compareTo(BigDecimal.ZERO) > 0
                ? totalBuyCost.divide(totalBuyQty, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // Hinweis: "realized" ist hier bewusst der Brutto-Verkaufserlös dieser Position
        // (Kostenbasis wird NICHT abgezogen) — dient nur noch der Positions-Tabelle,
        // NICHT mehr der Gesamt-Kachel "Realized" oben (die nutzt seit Fund 1/3 die
        // portfolio-weite Gewinn/Verlust-Berechnung aus HoldingsYearlyService).
        // quantityFiat ist bereits ein fertiger Gesamtbetrag (Menge × Preis) — bei
        // Fremdwährung reicht die Umrechnung über den Wechselkurs, OHNE nochmal mit
        // pricePerBtc zu multiplizieren (das quadrierte vorher fälschlich die Preis-Dimension).
        BigDecimal realized = txs.stream()
                .filter(tx -> tx.getType() == TransactionType.SELL)
                .filter(tx -> tx.getQuantityFiat() != null)
                .map(tx -> {
                    BigDecimal rate = tx.getExchangeRate() != null ? tx.getExchangeRate() : BigDecimal.ONE;
                    if (cp != null && cp.getCurrency().equals(tx.getCurrency())) {
                    	return tx.getQuantityFiat();
                    } else {
                    	return tx.getQuantityFiat().multiply(rate);
                    }
                })
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal invested = totalBuyCost;

        PositionDTO dto = new PositionDTO();
        dto.setId(p.getId());
        dto.setLabel(p.getLabel());
        dto.setType(p.getType());
        dto.setQuantity(quantity.setScale(8, RoundingMode.HALF_UP));
        dto.setQuantityInSats(quantity.multiply(SATS).setScale(0, RoundingMode.HALF_UP));
        dto.setAvgPurchasePrice(avgPurchasePrice);
        dto.setInvested(invested);
        dto.setRealized(realized);

        if (cp != null) {
            BigDecimal value    = quantity.multiply(cp.getPrice());
            BigDecimal gainLoss = value.subtract(invested);

            dto.setCurrentPrice(cp.getPrice());
            dto.setPriceDate(cp.getPriceDate());
            dto.setTotalValue(value.setScale(2, RoundingMode.HALF_UP));
            dto.setGainLoss(gainLoss.setScale(2, RoundingMode.HALF_UP));
            dto.setPerformancePct(invested.compareTo(BigDecimal.ZERO) > 0
                    ? gainLoss.divide(invested, 4, RoundingMode.HALF_UP)
                              .multiply(BigDecimal.valueOf(100))
                              .setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO);
        } else {
        	dto.setCurrentPrice(new BigDecimal(0));
        }
        return dto;
    }

    TransactionDTO toTransactionDTO(Transaction tx) {
        TransactionDTO dto = new TransactionDTO();
        dto.setId(tx.getId());
        dto.setPositionId(tx.getPosition().getId());
        dto.setPositionLabel(tx.getPosition().getLabel());
        dto.setPositionType(tx.getPosition().getType() != null ? tx.getPosition().getType().name() : null);
        dto.setType(tx.getType());
        dto.setDate(tx.getDate());
        dto.setQuantity(tx.getQuantity());
        dto.setPricePerBtc(tx.getPricePerBtc());
        dto.setFees(tx.getFees());
        dto.setFeesCurrency(tx.getFeesCurrency());
        dto.setCurrency(tx.getCurrency());
        dto.setComment(tx.getComment());
        dto.setExchangeRate(tx.getExchangeRate());
        dto.setTransferId(tx.getTransferId());
        dto.setTransactionId(tx.getTransactionId());
        dto.setDuplicate(tx.isDuplicate());

        if (tx.getQuantityFiat() != null) {
            //BigDecimal rate  = tx.getExchangeRate() != null ? tx.getExchangeRate() : BigDecimal.ONE;
            //BigDecimal total = tx.getQuantity().multiply(tx.getPricePerBtc()).multiply(rate);
            //if (tx.getFees() != null) total = total.add(tx.getFees());
            dto.setQuantityFiat(tx.getQuantityFiat().setScale(2, RoundingMode.HALF_UP));
        }
        return dto;
    }
    
    public String readCookie(HttpServletRequest request, String name, String defaultValue) {
    	if (request.getCookies() == null) return defaultValue;
    	return Arrays.stream(request.getCookies())
    			.filter(c -> name.equals(c.getName()))
    			.map(Cookie::getValue)
    			.findFirst()
    			.orElse(defaultValue);
    }
}
