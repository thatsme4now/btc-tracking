package com.thatsme4now.depot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thatsme4now.depot.entity.CurrentPrice;
import com.thatsme4now.depot.entity.PriceHistory;
import com.thatsme4now.depot.repository.CurrentPriceRepository;
import com.thatsme4now.depot.repository.PriceHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CoinGeckoService {

    private final PriceHistoryRepository priceHistoryRepo;
    private final CurrentPriceRepository currentPriceRepo;

    private final ObjectMapper  mapper       = new ObjectMapper();
    private final RestTemplate  restTemplate = new RestTemplate();

    private static final String TICKER = "BTC";

    private static final String OHLC_URL_TEMPLATE =
        "https://api.coingecko.com/api/v3/coins/bitcoin/ohlc?vs_currency=%s&days=1";

    /**
     * Loads 365 days of daily BTC OHLC candles from CoinGecko for the given currency.
     * Skips already existing dates. Updates current_price for that currency with latest close.
     *
     * @param currency e.g. "EUR", "USD", "THB"
     * @return number of newly saved entries
     */
    public int loadAndSaveHistory(String currency) {
        if (currency == null || currency.isBlank()) currency = "EUR";
        final String cur = currency.toUpperCase();
        log.info("Fetching BTC/{} OHLC from CoinGecko…", cur);
        String json = fetchJson(cur);

        try {
            JsonNode root = mapper.readTree(json);

            if (!root.isArray()) {
                throw new RuntimeException("Unexpected CoinGecko response format");
            }
            // Update current_price for this currency with the last candle's close
            JsonNode lastCandle = root.get(root.size() - 1);
            if (lastCandle != null && lastCandle.size() >= 5) {
                BigDecimal latestClose = toDecimal(lastCandle.get(2));
                LocalDate  latestDate  = Instant.ofEpochMilli(lastCandle.get(0).asLong())
                    .atZone(ZoneId.of("Europe/Berlin"))
                    .toLocalDate();

                CurrentPrice cp = currentPriceRepo
                    .findByTickerAndCurrency(TICKER, cur)
                    .orElse(new CurrentPrice());
                cp.setTicker(TICKER);
                cp.setCurrency(cur);
                cp.setPrice(latestClose);
                cp.setPriceDate(latestDate);
                cp.setLoadedAt(LocalDateTime.now());
                currentPriceRepo.save(cp);
                log.info("BTC/{} current price updated: {}", cur, latestClose);
            }
            return 1;

        } catch (Exception e) {
            log.error("CoinGecko error: {}", e.getMessage());
            throw new RuntimeException("CoinGecko error: " + e.getMessage());
        }
    }

    private String fetchJson(String currency) {
        String url = String.format(OHLC_URL_TEMPLATE, currency.toLowerCase());
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, defaultHeaders(), String.class);
            return response.getBody();
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode().value() == 429) {
                log.warn("CoinGecko 429 – waiting 60s before retry");
                try { Thread.sleep(60_000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                ResponseEntity<String> retry = restTemplate.exchange(
                    url, HttpMethod.GET, defaultHeaders(), String.class);
                return retry.getBody();
            }
            throw e;
        }
    }

    private HttpEntity<Void> defaultHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");
        headers.set("User-Agent", "Mozilla/5.0");
        return new HttpEntity<>(headers);
    }

    private BigDecimal toDecimal(JsonNode node) {
        if (node == null || node.isNull()) return null;
        return BigDecimal.valueOf(node.asDouble()).setScale(2, RoundingMode.HALF_UP);
    }
}