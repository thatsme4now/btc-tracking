package com.thatsme4now.depot.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bitcoinj.core.Base58;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.SegwitAddress;
import org.bitcoinj.core.Utils;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Derives addresses from an account-level xpub/ypub/zpub (BIP44/49/84, mainnet only) and scans them
 * for on-chain usage against the configured mempool instance, so a whole wallet can be imported at
 * once instead of one address at a time.
 *
 * Security-relevant invariants, deliberately kept simple enough to audit at a glance:
 * <ul>
 *   <li>The xpub string itself is read once, synchronously, in {@link #startScan} — only to derive
 *       from — and is never copied into {@link ScanState}, never logged (see the {@code catch}
 *       blocks below: every error message is built from fixed text or from mempool/address-level
 *       detail, never from the input string), and never persisted anywhere. Once the background
 *       thread this method starts finishes or is cancelled, no reference to it remains.</li>
 *   <li>Only derived, already-public addresses are ever sent anywhere (to {@link MempoolPriceService},
 *       exactly like the existing manual per-address flow) — never the xpub itself, so nothing beyond
 *       this application's own backend ever sees it.</li>
 *   <li>Every derivation from an xpub/ypub/zpub is public-key-only math (BIP32 CKDpub) — no private
 *       key ever exists anywhere in this class, even transiently.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class XpubScanService {

    private final MempoolPriceService mempoolPriceService;

    private static final NetworkParameters PARAMS = MainNetParams.get();

    /** Consecutive unused addresses (in a row, per chain) after which that chain's scan stops. */
    private static final int GAP_LIMIT = 10;
    /** Hard per-chain safety cap against a pathological loop — far above any real wallet's usage. */
    private static final int MAX_INDEX_PER_CHAIN = 2_000;
    /** BIP44/49/84 chains: 0 = external/receive, 1 = internal/change. */
    private static final int[] CHAINS = {0, 1};

    // Mainnet BIP32 extended-key version bytes (SLIP-132). Deliberately validated here ourselves
    // instead of relying on bitcoinj's own deserializeB58 (which only recognizes the xpub/zpub
    // headers natively, not ypub) — one explicit, auditable table covers all three address types
    // *and* lets us specifically detect and reject a pasted private key or testnet key with a clear
    // message, rather than an obscure parse failure.
    private static final int HEADER_XPUB = 0x0488B21E; // legacy P2PKH
    private static final int HEADER_YPUB = 0x049D7CB2; // P2SH-wrapped P2WPKH
    private static final int HEADER_ZPUB = 0x04B24746; // native P2WPKH (bech32)
    private static final Set<Integer> PRIVATE_HEADERS = Set.of(
            0x0488ADE4, // xprv
            0x049D7878, // yprv
            0x04B2430C  // zprv
    );

    public enum AddressType { LEGACY, P2SH_SEGWIT, NATIVE_SEGWIT }

    public enum ScanStatus { RUNNING, DONE, ERROR, CANCELLED }

    public static class XpubException extends RuntimeException {
        public XpubException(String message) {
            super(message);
        }
    }

    /** A parsed, ready-to-derive-from account key. Never holds or exposes the original xpub string. */
    public record ParsedAccountKey(DeterministicKey key, AddressType type) {}

    /** One address the scan found to have on-chain history, with everything needed to review/commit
     *  it without any further mempool call. */
    public record ScannedAddress(
            int id, int chain, int index, String address,
            long confirmedBalanceSats, long unconfirmedDeltaSats, String rawJson,
            List<MempoolPriceService.AddressTx> txs) {}

    public static class ScanState {
        public volatile ScanStatus status = ScanStatus.RUNNING;
        public volatile int chain = 0;
        public volatile int index = 0;
        public volatile int gapCount = 0;
        public volatile int checkedCount = 0;
        public volatile int foundCount = 0;
        public volatile String errorMessage;
        public volatile boolean cancelRequested = false;
        public volatile List<ScannedAddress> result = List.of();
    }

    private final Map<Long, ScanState> scans = new ConcurrentHashMap<>();

    // ── Key parsing / address derivation (pure, no I/O — unit-testable on its own) ──────────────

    /**
     * Parses an account-level xpub/ypub/zpub (mainnet only) into a key ready for child derivation,
     * plus the address type it implies. Throws {@link XpubException} with a message safe to show the
     * user for anything invalid, a private key, or a testnet/unsupported key.
     */
    public ParsedAccountKey parseAccountKey(String input) {
        if (input == null || input.isBlank()) {
            throw new XpubException("xpub darf nicht leer sein");
        }
        byte[] decoded;
        try {
            decoded = Base58.decodeChecked(input.trim());
        } catch (Exception e) {
            throw new XpubException("Ungültiges xpub-Format (kein gültiges Base58Check)");
        }
        if (decoded.length != 78) {
            throw new XpubException("Ungültiges xpub-Format (unerwartete Länge)");
        }

        int header = ((decoded[0] & 0xFF) << 24) | ((decoded[1] & 0xFF) << 16)
                | ((decoded[2] & 0xFF) << 8) | (decoded[3] & 0xFF);

        if (PRIVATE_HEADERS.contains(header)) {
            throw new XpubException("Das sieht nach einem PRIVATEN Extended Key aus (xprv/yprv/zprv) — "
                    + "bitte niemals einen privaten Schlüssel eingeben, sondern nur den öffentlichen xpub/ypub/zpub.");
        }

        AddressType type;
        if (header == HEADER_XPUB) type = AddressType.LEGACY;
        else if (header == HEADER_YPUB) type = AddressType.P2SH_SEGWIT;
        else if (header == HEADER_ZPUB) type = AddressType.NATIVE_SEGWIT;
        else throw new XpubException("Unbekanntes oder nicht unterstütztes Format — "
                + "unterstützt werden xpub/ypub/zpub (Mainnet).");

        // Rewrite to the plain xpub header before handing off to bitcoinj: its deserializer only
        // needs a structurally valid BIP32 payload from here — the version byte's remaining job was
        // telling us which address encoding to use, and `type` above already captured that.
        byte[] rewritten = decoded.clone();
        rewritten[0] = (byte) 0x04;
        rewritten[1] = (byte) 0x88;
        rewritten[2] = (byte) 0xB2;
        rewritten[3] = (byte) 0x1E;

        DeterministicKey key;
        try {
            key = DeterministicKey.deserialize(PARAMS, rewritten);
        } catch (Exception e) {
            throw new XpubException("xpub konnte nicht gelesen werden — ungültiges Format.");
        }
        return new ParsedAccountKey(key, type);
    }

    /** Derives the address at {@code index} on {@code chain} (0=receive, 1=change) — public-key-only
     *  math (BIP32 CKDpub), no private key ever involved. */
    public String deriveAddress(ParsedAccountKey account, int chain, int index) {
        DeterministicKey chainKey = HDKeyDerivation.deriveChildKey(account.key(), chain);
        DeterministicKey addrKey = HDKeyDerivation.deriveChildKey(chainKey, index);
        return switch (account.type()) {
            case LEGACY -> LegacyAddress.fromKey(PARAMS, addrKey).toString();
            case NATIVE_SEGWIT -> SegwitAddress.fromKey(PARAMS, addrKey).toBech32();
            case P2SH_SEGWIT -> {
                // BIP49: P2SH wrapping the P2WPKH witness program "OP_0 <20-byte-pubkey-hash>".
                Script witnessScript = ScriptBuilder.createP2WPKHOutputScript(addrKey);
                byte[] scriptHash = Utils.sha256hash160(witnessScript.getProgram());
                yield LegacyAddress.fromScriptHash(PARAMS, scriptHash).toString();
            }
        };
    }

    // ── Scan orchestration (background thread + in-memory, per-position state) ──────────────────

    public boolean isRunning(Long positionId) {
        ScanState s = scans.get(positionId);
        return s != null && s.status == ScanStatus.RUNNING;
    }

    public ScanState getState(Long positionId) {
        return scans.get(positionId);
    }

    public void cancel(Long positionId) {
        ScanState s = scans.get(positionId);
        if (s != null) s.cancelRequested = true;
    }

    /** Drops a finished scan's result (called once the frontend has committed or discarded it). */
    public void clear(Long positionId) {
        scans.remove(positionId);
    }

    /**
     * Validates and starts a scan in a background thread, then returns immediately. {@code xpub} is
     * only ever read here and inside the started thread's closure — see the class-level Javadoc for
     * the full set of handling invariants.
     *
     * @param alreadyKnownAddresses addresses already on this position (manually added or from an
     *                              earlier scan) — these still count as "used" for the gap counter,
     *                              but are not re-added to the result since they're already tracked.
     */
    public void startScan(Long positionId, String xpub, Set<String> alreadyKnownAddresses) {
        if (isRunning(positionId)) {
            throw new XpubException("Für diese Position läuft bereits ein xpub-Scan.");
        }
        ParsedAccountKey account = parseAccountKey(xpub); // validated synchronously before anything starts

        ScanState state = new ScanState();
        scans.put(positionId, state);

        Thread thread = new Thread(() -> runScan(account, alreadyKnownAddresses, state), "xpub-scan-" + positionId);
        thread.setDaemon(true);
        thread.start();
    }

    private void runScan(ParsedAccountKey account, Set<String> alreadyKnownAddresses, ScanState state) {
        try {
            List<ScannedAddress> found = new ArrayList<>();
            int nextId = 0;

            for (int chain : CHAINS) {
                int gap = 0;
                for (int index = 0; gap < GAP_LIMIT && index < MAX_INDEX_PER_CHAIN; index++) {
                    if (state.cancelRequested) {
                        state.status = ScanStatus.CANCELLED;
                        return;
                    }
                    state.chain = chain;
                    state.index = index;

                    String address = deriveAddress(account, chain, index);
                    boolean used;

                    if (alreadyKnownAddresses.contains(address)) {
                        // Already tracked on this position — counts toward the gap, but nothing new
                        // to surface: it's already there.
                        used = true;
                    } else {
                        MempoolPriceService.AddressInfo info = mempoolPriceService.fetchAddressInfo(address);
                        used = info.chainTxCount() > 0 || info.mempoolTxCount() > 0;
                        if (used) {
                            List<MempoolPriceService.AddressTx> txs = mempoolPriceService.fetchAddressTxs(address, 50);
                            found.add(new ScannedAddress(nextId++, chain, index, address,
                                    info.confirmedBalanceSats(), info.unconfirmedDeltaSats(), info.rawJson(), txs));
                            state.foundCount = found.size();
                        }
                    }

                    gap = used ? 0 : gap + 1;
                    state.gapCount = gap;
                    state.checkedCount++;
                }
            }

            state.result = found;
            state.status = ScanStatus.DONE;
        } catch (MempoolPriceService.MempoolException e) {
            state.errorMessage = e.getMessage();
            state.status = ScanStatus.ERROR;
        } catch (Exception e) {
            // Logs the exception only — every message on this path is built from fixed text or
            // mempool/address-level detail (see fetchAddressInfo/fetchAddressTxs), never the xpub.
            log.error("xpub scan failed", e);
            state.errorMessage = "Unerwarteter Fehler: " + e.getMessage();
            state.status = ScanStatus.ERROR;
        }
    }
}
