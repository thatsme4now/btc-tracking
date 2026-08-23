package com.thatsme4now.depot.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * A Bitcoin address optionally attached to a {@link Position}, used to query
 * on-chain balance/history via a user-configured mempool instance (see
 * AppSettings#mempoolHost/mempoolPort, MempoolPriceService). Purely optional
 * — a position can have zero, one, or many addresses.
 *
 * lastFetch*: cached result of the most recent live balance lookup, so a
 * later fetch can detect and flag a change without needing an extra
 * round-trip just to compare — and so the last-known state (balance + recent
 * txs) can be shown immediately when the balance dialog opens, without any
 * mempool call at all. lastFetchJson stores the raw GET /api/address/:address
 * response (chain_stats/mempool_stats) for debugging/reference and so it can
 * be re-parsed for a cached read; lastFetchBalanceSats is the same confirmed
 * balance denormalized into its own column so comparisons don't need to
 * re-parse JSON. lastFetchTxsJson caches the up-to-50 recent transactions
 * from GET /api/address/:address/txs (see MempoolPriceService#serializeAddressTxs) —
 * "already tracked" / match-candidate status per tx is deliberately NOT part of
 * this cache and is always recomputed live against the current Transaction
 * table when read, since that's a cheap local DB check and freezing it would
 * go stale as soon as the user imports/edits transactions elsewhere.
 */
@Data
@Entity
@Table(name = "position_address",
       indexes = { @Index(name = "idx_position_address_position", columnList = "position_id") })
public class PositionAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // EAGER (not LAZY): this entity is read/returned from repository calls outside an active
    // Hibernate session in the controller (e.g. addr.getPosition().getId() ownership checks) —
    // see Transaction#position for the same reasoning/precedent.
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "position_id", nullable = false)
    private Position position;

    /** Bitcoin address. Soft-validated only (basic format check), never checksum-verified. */
    @Column(nullable = false, length = 120)
    private String address;

    /** Optional free-text label for this address, e.g. "Cold Storage UTXO". */
    @Column(length = 100)
    private String label;

    /** Raw JSON of the most recent GET /api/address/:address response, or NULL if never fetched. */
    @Column(name = "last_fetch_json", columnDefinition = "TEXT")
    private String lastFetchJson;

    /** Confirmed balance (chain_stats.funded_txo_sum - spent_txo_sum) in satoshis from the last fetch, for change detection. */
    @Column(name = "last_fetch_balance_sats")
    private Long lastFetchBalanceSats;

    /** Cached up-to-50 recent transactions from the last fetch, serialized — see class-level comment. */
    @Column(name = "last_fetch_txs_json", columnDefinition = "TEXT")
    private String lastFetchTxsJson;

    @Column(name = "last_fetch_at")
    private LocalDateTime lastFetchAt;
}
