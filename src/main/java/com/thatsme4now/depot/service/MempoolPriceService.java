package com.thatsme4now.depot.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thatsme4now.depot.entity.AppSettings;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Optional client for a user-configured, self-hosted mempool instance
 * (e.g. the mempool app on the user's own Umbrel/LAN, see AppSettings#
 * mempoolHost/mempoolPort) — used to fetch the current BTC price on demand
 * and to fill in missing monthly (Ultimo) reference prices in the
 * "Jahresansicht". Disabled/opt-in: only called when the user has
 * explicitly configured a host/port in the settings.
 *
 * The backend (not the browser) performs this HTTP call, so it works
 * regardless of the mempool instance's CORS configuration — but "localhost"
 * in the configured host is relative to wherever this Java process runs,
 * not the user's browser. Only http:// against the configured host/port is
 * ever used — no arbitrary scheme, no other host.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MempoolPriceService {

    /** Currencies mempool's price endpoints support — https://mempool.space/docs/api/rest (Prices). Notably no THB. */
    public static final List<String> SUPPORTED_CURRENCIES = List.of("USD", "EUR", "GBP", "CAD", "CHF", "AUD", "JPY");

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    // Hostname or IPv4/IPv6-ish literal — letters, digits, '.', '-', ':'. No scheme, no path, no userinfo.
    private static final Pattern HOST_PATTERN = Pattern.compile("^[a-zA-Z0-9.\\-:]+$");

    // HTTP_1_1 explicitly: java.net.http.HttpClient defaults to preferring HTTP/2, which for
    // plain http:// (no TLS/ALPN) means it sends an "Upgrade: h2c" header on the initial
    // request. Many lightweight/embedded servers (e.g. a self-hosted mempool instance) don't
    // handle that upgrade attempt and just close the connection without responding, which
    // surfaces here as "HTTP/1.1 header parser received no bytes" — even though a browser
    // (which never attempts h2c over plain HTTP) reaches the exact same URL just fine.
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private final DepotService depotService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Thrown for anything that should surface as a plain-text error to the user (not configured, unreachable, timeout, bad response). */
    public static class MempoolException extends RuntimeException {
        public MempoolException(String message) {
            super(message);
        }
    }

    public record PricePoint(Instant time, BigDecimal price) {}

    /** Validates a host/port pair as entered in the settings UI. Both null/blank is valid (feature disabled). Throws otherwise. */
    public static void validateHostPort(String host, Integer port) {
        boolean hostBlank = (host == null || host.isBlank());
        boolean portBlank = (port == null);
        if (hostBlank && portBlank) return; // disabled — ok
        if (hostBlank != portBlank) {
            throw new MempoolException("Host und Port müssen beide gesetzt oder beide leer sein");
        }
        if (!HOST_PATTERN.matcher(host.trim()).matches()) {
            throw new MempoolException("Ungültiger Host: " + host);
        }
        if (port < 1 || port > 65535) {
            throw new MempoolException("Ungültiger Port: " + port + " (erlaubt: 1–65535)");
        }
    }

    private String resolveBaseUrl() {
        AppSettings settings = depotService.getAppSettings();
        String host = settings.getMempoolHost();
        Integer port = settings.getMempoolPort();
        if (host == null || host.isBlank() || port == null) {
            throw new MempoolException("Mempool-Host/Port ist nicht konfiguriert (Einstellungen → Mempool-Integration)");
        }
        validateHostPort(host, port);
        return "http://" + host.trim() + ":" + port;
    }

    /** GET /api/v1/prices — current price for every currency the mempool instance returns. Keys are uppercase currency codes. */
    public Map<String, BigDecimal> fetchCurrentPrices() {
        String url = resolveBaseUrl() + "/api/v1/prices";
        JsonNode node = getJson(url);
        Map<String, BigDecimal> result = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey().toUpperCase();
            if (SUPPORTED_CURRENCIES.contains(key) && entry.getValue().isNumber()) {
                result.put(key, entry.getValue().decimalValue());
            }
        }
        if (result.isEmpty()) {
            throw new MempoolException("Unerwartetes Antwortformat von " + url);
        }
        return result;
    }

    /**
     * GET /api/v1/historical-price?currency=XXX (ohne timestamp) — komplette
     * verfügbare Preishistorie für eine Währung, ein Aufruf statt vieler
     * einzelner Zeitpunkt-Abfragen. Der Aufrufer (siehe MonthlyPriceService)
     * sucht sich daraus selbst den nächstliegenden Datenpunkt je Monat.
     */
    public List<PricePoint> fetchHistoricalSeries(String currency) {
        String cur = currency.toUpperCase();
        if (!SUPPORTED_CURRENCIES.contains(cur)) {
            throw new MempoolException("Währung " + cur + " wird von mempool nicht unterstützt");
        }
        String url = resolveBaseUrl() + "/api/v1/historical-price?currency=" + cur;
        JsonNode node = getJson(url);

        JsonNode arr = null;
        if (node.isArray()) {
            arr = node;
        } else if (node.has("prices") && node.get("prices").isArray()) {
            arr = node.get("prices");
        } else if (node.has("data") && node.get("data").isArray()) {
            arr = node.get("data");
        }
        if (arr == null) {
            throw new MempoolException("Unerwartetes Antwortformat von " + url);
        }

        List<PricePoint> points = new ArrayList<>();
        for (JsonNode entry : arr) {
            JsonNode timeNode = entry.has("time") ? entry.get("time") : null;
            JsonNode priceNode = entry.has(cur) ? entry.get(cur) : null;
            if (timeNode == null || priceNode == null || !timeNode.isNumber() || !priceNode.isNumber()) continue;
            points.add(new PricePoint(Instant.ofEpochSecond(timeNode.asLong()), priceNode.decimalValue()));
        }
        if (points.isEmpty()) {
            throw new MempoolException("Keine historischen Preisdaten für " + cur + " von der mempool-API erhalten");
        }
        return points;
    }

    /** Result of GET /api/address/:address — see https://mempool.space/docs/api/rest (Addresses). */
    public record AddressInfo(
            long confirmedBalanceSats,   // chain_stats.funded_txo_sum - spent_txo_sum
            long unconfirmedDeltaSats,   // mempool_stats.funded_txo_sum - spent_txo_sum, can be negative
            long chainTxCount,
            long mempoolTxCount,
            String rawJson) {}

    /** One entry from GET /api/address/:address/txs, reduced to what the address-import UI needs. */
    public record AddressTx(
            String txid,
            boolean confirmed,
            Long blockTimeEpochSeconds, // null if unconfirmed
            long netSats) {}            // this address's net change in this tx: received - spent, in satoshis

    // Bitcoin addresses: base58 (legacy/P2SH, 1.../3...) or bech32/bech32m (bc1...). Soft length/charset
    // check only — never a checksum validation. Deliberately permissive (testnet prefixes etc. also pass).
    private static final Pattern ADDRESS_PATTERN = Pattern.compile("^[a-zA-Z0-9]{20,90}$");

    /** Soft-validates an address string as entered in the position edit dialog. Throws on obvious typos. */
    public static void validateAddress(String address) {
        if (address == null || address.isBlank()) {
            throw new MempoolException("Adresse darf nicht leer sein");
        }
        if (!ADDRESS_PATTERN.matcher(address.trim()).matches()) {
            throw new MempoolException("Ungültiges Adressformat: " + address);
        }
    }

    /** GET /api/address/:address — confirmed balance, unconfirmed delta, tx counts. */
    public AddressInfo fetchAddressInfo(String address) {
        String url = resolveBaseUrl() + "/api/address/" + java.net.URLEncoder.encode(address.trim(), java.nio.charset.StandardCharsets.UTF_8);
        return parseAddressInfo(getRaw(url), url);
    }

    /**
     * Parses the same shape {@link #fetchAddressInfo} returns, but from an already-held raw JSON
     * string instead of making a live HTTP call — used to reconstruct the cached "last fetch" view
     * (PositionAddress#lastFetchJson) without hitting the mempool instance again.
     */
    public AddressInfo parseAddressInfo(String rawJson, String sourceForErrorMessage) {
        JsonNode node;
        try {
            node = objectMapper.readTree(rawJson);
        } catch (IOException e) {
            throw new MempoolException("Unerwartetes Antwortformat von " + sourceForErrorMessage);
        }
        JsonNode chain = node.get("chain_stats");
        JsonNode mempool = node.get("mempool_stats");
        if (chain == null || mempool == null) {
            throw new MempoolException("Unerwartetes Antwortformat von " + sourceForErrorMessage);
        }
        long confirmed = chain.path("funded_txo_sum").asLong(0) - chain.path("spent_txo_sum").asLong(0);
        long unconfirmedDelta = mempool.path("funded_txo_sum").asLong(0) - mempool.path("spent_txo_sum").asLong(0);
        return new AddressInfo(confirmed, unconfirmedDelta,
                chain.path("tx_count").asLong(0), mempool.path("tx_count").asLong(0), rawJson);
    }

    /** Serializes a tx list to a compact JSON array, for caching in PositionAddress#lastFetchTxsJson. */
    public String serializeAddressTxs(List<AddressTx> txs) {
        com.fasterxml.jackson.databind.node.ArrayNode arr = objectMapper.createArrayNode();
        for (AddressTx tx : txs) {
            com.fasterxml.jackson.databind.node.ObjectNode n = arr.addObject();
            n.put("txid", tx.txid());
            n.put("confirmed", tx.confirmed());
            if (tx.blockTimeEpochSeconds() != null) n.put("blockTime", tx.blockTimeEpochSeconds()); else n.putNull("blockTime");
            n.put("netSats", tx.netSats());
        }
        return arr.toString();
    }

    /** Inverse of {@link #serializeAddressTxs} — reconstructs the cached tx list, or an empty list if json is null/blank. */
    public List<AddressTx> parseAddressTxs(String json) {
        if (json == null || json.isBlank()) return List.of();
        JsonNode arr;
        try {
            arr = objectMapper.readTree(json);
        } catch (IOException e) {
            return List.of(); // corrupt cache — treat as "nothing cached" rather than failing the request
        }
        List<AddressTx> result = new ArrayList<>();
        for (JsonNode n : arr) {
            String txid = n.path("txid").asText(null);
            if (txid == null) continue;
            Long blockTime = n.hasNonNull("blockTime") ? n.get("blockTime").asLong() : null;
            result.add(new AddressTx(txid, n.path("confirmed").asBoolean(false), blockTime, n.path("netSats").asLong(0)));
        }
        return result;
    }

    /**
     * GET /api/address/:address/txs — up to `limit` most recent transactions (mempool.space returns
     * unconfirmed first, then confirmed, newest first). Computes this address's net satoshi delta per
     * transaction from vin/vout so the caller doesn't need to re-parse the raw tx shape.
     */
    public List<AddressTx> fetchAddressTxs(String address, int limit) {
        String url = resolveBaseUrl() + "/api/address/" + java.net.URLEncoder.encode(address.trim(), java.nio.charset.StandardCharsets.UTF_8) + "/txs";
        JsonNode arr = getJson(url);
        if (!arr.isArray()) {
            throw new MempoolException("Unerwartetes Antwortformat von " + url);
        }
        String addr = address.trim();
        List<AddressTx> result = new ArrayList<>();
        for (JsonNode tx : arr) {
            if (result.size() >= limit) break;
            String txid = tx.path("txid").asText(null);
            if (txid == null) continue;

            long received = 0;
            for (JsonNode vout : tx.path("vout")) {
                if (addr.equals(vout.path("scriptpubkey_address").asText(null))) {
                    received += vout.path("value").asLong(0);
                }
            }
            long spent = 0;
            for (JsonNode vin : tx.path("vin")) {
                JsonNode prevout = vin.get("prevout");
                if (prevout != null && addr.equals(prevout.path("scriptpubkey_address").asText(null))) {
                    spent += prevout.path("value").asLong(0);
                }
            }

            JsonNode status = tx.path("status");
            boolean confirmed = status.path("confirmed").asBoolean(false);
            Long blockTime = confirmed && status.hasNonNull("block_time") ? status.get("block_time").asLong() : null;
            result.add(new AddressTx(txid, confirmed, blockTime, received - spent));
        }
        return result;
    }

    private String getRaw(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new MempoolException("mempool-API antwortete mit HTTP " + response.statusCode() + " (" + url + ")");
            }
            return response.body();
        } catch (IOException e) {
            log.warn("mempool-API nicht erreichbar: {}", url, e);
            throw new MempoolException("mempool-API nicht erreichbar (Host/Port prüfen): " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MempoolException("Anfrage an die mempool-API wurde unterbrochen");
        }
    }

    private JsonNode getJson(String url) {
        try {
            return objectMapper.readTree(getRaw(url));
        } catch (IOException e) {
            throw new MempoolException("Unerwartetes Antwortformat von " + url);
        }
    }
}
