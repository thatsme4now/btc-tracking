package com.thatsme4now.depot.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import lombok.extern.slf4j.Slf4j;

/**
 * Free, no-API-key source of BTC/EUR and BTC/USD historical closes, used to
 * backfill the monthly_price table — see MonthlyPriceService#backfill.
 *
 * We originally used CoinGecko's market_chart/range endpoint for this (see
 * CoinGeckoService), but CoinGecko's free/demo tier now rejects any request
 * for data older than 365 days (HTTP 401, error_code 10012) — discovered in
 * production, not documented anywhere obvious beforehand. Kraken's public
 * OHLC endpoint has no such time-range restriction and needs no API key.
 *
 * IMPORTANT gotcha (also discovered the hard way): Kraken's OHLC endpoint
 * does NOT return "up to 720 candles starting at `since`" — it always
 * returns the most recent ≤720 candles that are newer than `since`, i.e. it
 * truncates from the RECENT end. With daily candles (interval=1440), 13
 * years is ~4700 candles, so a since=2013 request silently came back only
 * as far as ~2024 (the last 720 days), never actually reaching 2013. Weekly
 * candles (interval=10080) for the same 13-year span are only ~670 rows —
 * comfortably under the 720 cap — so a SINGLE weekly request, with no
 * pagination at all, returns Kraken's full history for the pair (verified:
 * XBTEUR starts 2013-09-05). The Ultimo (end-of-month) price this yields is
 * therefore only accurate to within ~1 week, not the exact last day of the
 * month — an acceptable trade-off for this informational chart, consistent
 * with the app's other historical-price data (also approximate, see
 * HistoricalPriceSeeder).
 *
 * Kraken has no BTC/THB pair at all — THB is derived separately in
 * MonthlyPriceService from the USD history using a rough yearly FX rate
 * (see THB_PER_USD_YEARLY there), not fetched here.
 */
@Slf4j
@Service
public class KrakenService {

    private static final String OHLC_URL_TEMPLATE =
        "https://api.kraken.com/0/public/OHLC?pair=%s&interval=10080&since=%d";

    /** Harmless if earlier than a pair's actual listing date — Kraken just returns what it has. */
    private static final long EARLIEST_EPOCH = LocalDate.of(2013, 1, 1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();

    private final ObjectMapper mapper       = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * @param currency "EUR" or "USD" — any other value returns an empty map (no Kraken pair for it)
     * @return weekly closes bucketed down to one Ultimo-ish (last available week of each month) price per {@link YearMonth}
     */
    public Map<YearMonth, BigDecimal> loadMonthlyHistory(String currency) {
        String pair = pairFor(currency);
        if (pair == null) return Map.of();

        log.info("Fetching BTC/{} full weekly history from Kraken…", currency);
        Map<YearMonth, BigDecimal> monthly = new LinkedHashMap<>();

        String url  = String.format(OHLC_URL_TEMPLATE, pair, EARLIEST_EPOCH);
        String json = fetchUrl(url);

        JsonNode root   = parseAndCheck(json);
        JsonNode result = root.get("result");
        if (result == null) return monthly;

        String dataKey = firstNonLastField(result);
        if (dataKey == null) return monthly;
        JsonNode rows = result.get(dataKey);
        if (rows == null || !rows.isArray()) return monthly;

        // Rows are ordered ascending by date — for each month we just keep overwriting
        // as we go, so the LAST candle seen (latest week) wins, giving an end-of-month
        // approximation without needing a second pass.
        for (JsonNode row : rows) {
            if (row.size() < 5) continue;
            long epochSecs = row.get(0).asLong();
            BigDecimal close = new BigDecimal(row.get(4).asText());
            LocalDate date = Instant.ofEpochSecond(epochSecs).atZone(ZoneOffset.UTC).toLocalDate();
            monthly.put(YearMonth.from(date), close.setScale(2, RoundingMode.HALF_UP));
        }

        return monthly;
    }

    private JsonNode parseAndCheck(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode errors = root.get("error");
            if (errors != null && errors.isArray() && !errors.isEmpty()) {
                throw new RuntimeException("Kraken error: " + errors);
            }
            return root;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Kraken response parse error: " + e.getMessage());
        }
    }

    private String firstNonLastField(JsonNode result) {
        Iterator<String> names = result.fieldNames();
        while (names.hasNext()) {
            String n = names.next();
            if (!"last".equals(n)) return n;
        }
        return null;
    }

    private String pairFor(String currency) {
        if (currency == null) return null;
        return switch (currency.toUpperCase()) {
            case "EUR" -> "XBTEUR";
            case "USD" -> "XBTUSD";
            default -> null;
        };
    }

    private String fetchUrl(String url) {
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, defaultHeaders(), String.class);
        return response.getBody();
    }

    private HttpEntity<Void> defaultHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");
        headers.set("User-Agent", "Mozilla/5.0");
        return new HttpEntity<>(headers);
    }
}
