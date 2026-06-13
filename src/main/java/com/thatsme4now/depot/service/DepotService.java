package com.thatsme4now.depot.service;

import com.thatsme4now.depot.dto.PositionDTO;
import com.thatsme4now.depot.dto.TransactionDTO;
import com.thatsme4now.depot.entity.*;
import com.thatsme4now.depot.repository.*;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DepotService {

    private final PositionRepository     positionRepo;
    private final TransactionRepository  transactionRepo;
    private final CurrentPriceRepository currentPriceRepo;
    private final PriceHistoryRepository priceHistoryRepo;
    private final CoinGeckoService       coinGeckoService;

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

    // ── Transactions ──────────────────────────────────────

    public long getTransactionCount() {
        return transactionRepo.count();
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

    public int refreshPrices(String currency) {
        return coinGeckoService.loadAndSaveHistory(normalizeCurrency(currency));
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

        BigDecimal totalBuyCost = txs.stream()
                .filter(tx -> tx.getType() == TransactionType.BUY && tx.getPricePerBtc() != null)
                .map(tx -> {
                    BigDecimal rate = tx.getExchangeRate() != null ? tx.getExchangeRate() : BigDecimal.ONE;
                    return tx.getQuantity().multiply(tx.getPricePerBtc()).multiply(rate);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal avgPurchasePrice = totalBuyQty.compareTo(BigDecimal.ZERO) > 0
                ? totalBuyCost.divide(totalBuyQty, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal realized = txs.stream()
                .filter(tx -> tx.getType() == TransactionType.SELL)
                .map(Transaction::getQuantityFiat)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal invested = quantity.multiply(avgPurchasePrice).setScale(2, RoundingMode.HALF_UP);

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

    private TransactionDTO toTransactionDTO(Transaction tx) {
        TransactionDTO dto = new TransactionDTO();
        dto.setId(tx.getId());
        dto.setPositionId(tx.getPosition().getId());
        dto.setPositionLabel(tx.getPosition().getLabel());
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
