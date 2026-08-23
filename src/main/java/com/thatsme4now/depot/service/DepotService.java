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
import com.thatsme4now.depot.entity.PositionAddress;
import com.thatsme4now.depot.entity.PriceHistory;
import com.thatsme4now.depot.entity.Transaction;
import com.thatsme4now.depot.entity.TransactionType;
import com.thatsme4now.depot.repository.AppSettingsRepository;
import com.thatsme4now.depot.repository.CurrentPriceRepository;
import com.thatsme4now.depot.repository.PositionAddressRepository;
import com.thatsme4now.depot.repository.PositionRepository;
import com.thatsme4now.depot.repository.PriceHistoryRepository;
import com.thatsme4now.depot.repository.TransactionRepository;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * Core CRUD and mapping service for positions, transactions, current/historical
 * prices and app settings — the shared data layer behind most of the app's
 * REST endpoints.
 */
@Service
@RequiredArgsConstructor
public class DepotService {

    private final PositionRepository        positionRepo;
    private final PositionAddressRepository positionAddressRepo;
    private final TransactionRepository     transactionRepo;
    private final CurrentPriceRepository    currentPriceRepo;
    private final PriceHistoryRepository    priceHistoryRepo;
    private final AppSettingsRepository     appSettingsRepo;

    private static final String     TICKER = "BTC";
    private static final BigDecimal SATS   = BigDecimal.valueOf(100_000_000);

    // ── Positions ─────────────────────────────────────────

    /** Lists all positions as DTOs, with value/gain fields priced in the given currency. */
    public List<PositionDTO> getAllPositions(String currency) {
        String cur = normalizeCurrency(currency);
        CurrentPrice cp = currentPriceRepo.findByTickerAndCurrency(TICKER, cur).orElse(null);
        // Loaded once for all positions (not per-position) to avoid an N+1 query — this is
        // just the cached on-chain balance from each address's last fetch, no mempool call.
        java.util.Map<Long, List<PositionAddress>> addressesByPosition = positionAddressRepo.findAll().stream()
                .collect(Collectors.groupingBy(a -> a.getPosition().getId()));
        return positionRepo.findAll().stream()
                .map(p -> toDTO(p, cp, addressesByPosition.getOrDefault(p.getId(), List.of())))
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

    // ── Position Addresses ────────────────────────────────

    public List<PositionAddress> getPositionAddresses(Long positionId) {
        return positionAddressRepo.findByPositionIdOrderByIdAsc(positionId);
    }

    public Optional<PositionAddress> getPositionAddress(Long id) {
        return positionAddressRepo.findById(id);
    }

    public PositionAddress savePositionAddress(PositionAddress address) {
        return positionAddressRepo.save(address);
    }

    /** Plain (address, label) pair as submitted from the position edit dialog — id is null for a new address. */
    public record AddressInput(Long id, String address, String label) {}

    /**
     * Replaces a position's address list with the given inputs, matched by id where present:
     * an existing address (id present, still in the new list) has its address/label updated —
     * but its last_fetch_* cache is preserved unless the address string itself changed (a
     * different address invalidates any cached balance). Ids no longer present are deleted.
     * Inputs without an id (or whose id doesn't belong to this position) are inserted as new.
     */
    public void replacePositionAddresses(Position position, List<AddressInput> inputs) {
        List<PositionAddress> existing = getPositionAddresses(position.getId());
        java.util.Map<Long, PositionAddress> existingById = existing.stream()
                .collect(Collectors.toMap(PositionAddress::getId, a -> a));

        java.util.Set<Long> keepIds = new java.util.HashSet<>();
        for (AddressInput input : inputs) {
            PositionAddress entity = input.id() != null ? existingById.get(input.id()) : null;
            if (entity == null) {
                entity = new PositionAddress();
                entity.setPosition(position);
            } else if (!Objects.equals(entity.getAddress(), input.address())) {
                // address string changed — the cached balance/tx-list no longer applies
                entity.setLastFetchJson(null);
                entity.setLastFetchBalanceSats(null);
                entity.setLastFetchTxsJson(null);
                entity.setLastFetchAt(null);
            }
            entity.setAddress(input.address());
            entity.setLabel(input.label());
            positionAddressRepo.save(entity);
            if (entity.getId() != null) keepIds.add(entity.getId());
        }

        for (PositionAddress old : existing) {
            if (!keepIds.contains(old.getId())) {
                positionAddressRepo.deleteById(old.getId());
            }
        }
    }

    public void deletePositionAddress(Long id) {
        positionAddressRepo.deleteById(id);
    }

    // ── Current Price ─────────────────────────────────────

    public Optional<CurrentPrice> getCurrentPrice(String currency) {
        return currentPriceRepo.findByTickerAndCurrency(TICKER, normalizeCurrency(currency));
    }

    public CurrentPrice saveCurrentPrice(CurrentPrice cp) {
        return currentPriceRepo.save(cp);
    }

    // ── App Settings (singleton row) ───────────────────────

    /** Returns the single app settings row, creating it with defaults on first access. */
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

    /** Whether an on-chain TXID is already tracked by any transaction — used by the address-import feature to avoid duplicates. */
    public boolean transactionExistsByBlockchainTxId(String blockchainTxId) {
        return transactionRepo.existsByBlockchainTxId(blockchainTxId);
    }

    /** Existing, not-yet-TXID'd TRANSFER_IN/OUT transactions on a position that could be the same on-chain tx — see TransactionRepository. */
    public List<Transaction> findMatchCandidates(Long positionId, TransactionType type, BigDecimal quantity,
                                                  java.time.LocalDateTime start, java.time.LocalDateTime end) {
        return transactionRepo.findByPositionIdAndTypeAndBlockchainTxIdIsNullAndQuantityAndDateBetween(
                positionId, type, quantity, start, end);
    }

    public void deleteTransaction() {
        transactionRepo.deleteAll();
    }

    public Optional<Transaction> getTransaction(Long id) {
        return transactionRepo.findById(id);
    }

    /**
     * Syncs {@code tx}'s blockchainTxId onto its paired TRANSFER_IN/
     * TRANSFER_OUT counterpart(s) (same transferId, see Transaction#transferId)
     * — a self-transfer pair is physically one on-chain transaction, so both
     * legs share the same TXID. No-op if tx has no transferId.
     * <p>
     * Two distinct behaviors depending on whether {@code tx}'s TXID was just
     * set or just cleared:
     * <ul>
     *   <li><b>Set</b> (non-blank): only fills an empty counterpart; an
     *       existing, differing value on the other side is left untouched
     *       (no silent overwrite on conflict).</li>
     *   <li><b>Cleared</b> (null/blank): propagates the removal to every
     *       paired leg unconditionally, even if it currently holds a
     *       different TXID — clearing on one side means "this TXID no longer
     *       belongs to this transfer" for the pair as a whole.</li>
     * </ul>
     */
    public void syncBlockchainTxIdToPairedTransfer(Transaction tx) {
        if (tx.getTransferId() == null) {
            return;
        }
        String newTxId = tx.getBlockchainTxId();
        boolean cleared = newTxId == null || newTxId.isBlank();

        for (Transaction other : transactionRepo.findByTransferId(tx.getTransferId())) {
            if (other.getId() != null && other.getId().equals(tx.getId())) continue;
            if (cleared) {
                if (other.getBlockchainTxId() != null && !other.getBlockchainTxId().isBlank()) {
                    other.setBlockchainTxId(null);
                    transactionRepo.save(other);
                }
            } else if (other.getBlockchainTxId() == null || other.getBlockchainTxId().isBlank()) {
                other.setBlockchainTxId(newTxId);
                transactionRepo.save(other);
            }
        }
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

    /** Net BTC quantity (BUY/TRANSFER_IN/DEPOSIT positive, SELL/TRANSFER_OUT/WITHDRAW negative) for the given transactions. */
    private BigDecimal computeQuantity(List<Transaction> txs) {
        return txs.stream()
                .map(tx -> switch (tx.getType()) {
                    case BUY, TRANSFER_IN, DEPOSIT   -> tx.getQuantity();
                    case SELL, TRANSFER_OUT, WITHDRAW -> tx.getQuantity().negate();
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Net BTC quantity currently tracked for a position — same figure shown in the Wallets/Exchanges table. */
    public BigDecimal getPositionQuantity(Long positionId) {
        return computeQuantity(transactionRepo.findByPositionIdOrderByDateAsc(positionId))
                .setScale(8, RoundingMode.HALF_UP);
    }

    /** Maps a position + its transactions into a {@link PositionDTO} with computed quantity, cost basis and gain/loss. */
    private PositionDTO toDTO(Position p, CurrentPrice cp, List<PositionAddress> addresses) {
        List<Transaction> txs = transactionRepo.findByPositionIdOrderByDateAsc(p.getId());

        BigDecimal quantity = computeQuantity(txs);

        BigDecimal totalBuyQty = txs.stream()
                .filter(tx -> tx.getType() == TransactionType.BUY)
                .map(Transaction::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Buy fees count toward the cost basis, consistent with the main table's
        // gain/loss column and the "gain/loss per buy" chart.
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

        // "realized" here is the gross sell proceeds of this position (cost basis is NOT
        // subtracted) — used only by the position table, not the portfolio-wide "Realized"
        // tile (see HoldingsYearlyService for that). quantityFiat is already a total amount
        // (quantity × price), so foreign currency just needs the exchange rate applied.
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

        // On-chain balance for the position table's "On-Chain" column — purely from each address's
        // cached last-fetch snapshot (see PositionAddress#lastFetchBalanceSats), never a live mempool
        // call here. Null (not 0) when there's nothing to show yet — either no addresses configured,
        // or addresses configured but none of them fetched even once.
        dto.setHasAddresses(!addresses.isEmpty());
        long fetchedCount = addresses.stream().filter(a -> a.getLastFetchBalanceSats() != null).count();
        if (fetchedCount > 0) {
            long onchainSats = addresses.stream()
                    .filter(a -> a.getLastFetchBalanceSats() != null)
                    .mapToLong(PositionAddress::getLastFetchBalanceSats)
                    .sum();
            dto.setOnchainBalanceSats(onchainSats);
            dto.setOnchainBalanceBtc(BigDecimal.valueOf(onchainSats).divide(SATS, 8, RoundingMode.HALF_UP));
            dto.setOnchainBalancePartial(fetchedCount < addresses.size());
            dto.setOnchainBalanceDiffers(onchainSats != dto.getQuantityInSats().longValue());
        }

        return dto;
    }

    /** Maps a {@link Transaction} entity to its DTO. */
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
        dto.setBlockchainTxId(tx.getBlockchainTxId());
        dto.setDuplicate(tx.isDuplicate());

        if (tx.getQuantityFiat() != null) {
            dto.setQuantityFiat(tx.getQuantityFiat().setScale(2, RoundingMode.HALF_UP));
        }
        return dto;
    }

    /** Reads a cookie value by name, or returns {@code defaultValue} if absent. */
    public String readCookie(HttpServletRequest request, String name, String defaultValue) {
    	if (request.getCookies() == null) return defaultValue;
    	return Arrays.stream(request.getCookies())
    			.filter(c -> name.equals(c.getName()))
    			.map(Cookie::getValue)
    			.findFirst()
    			.orElse(defaultValue);
    }
}
