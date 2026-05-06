package com.claude.reportAi.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

@Service
@Slf4j
public class ExchangeRateService {

    private final RestClient restClient;
    private final Duration cacheTtl;
    private final double fallbackUsdPerEur;

    private volatile CachedRate cachedRate;

    public ExchangeRateService(
            @Value("${app.exchange-rate.base-url:https://api.frankfurter.app}") String baseUrl,
            @Value("${app.exchange-rate.cache-ttl:PT6H}") Duration cacheTtl,
            @Value("${app.exchange-rate.fallback-usd-per-eur:1.15}") double fallbackUsdPerEur) {

        this.cacheTtl = cacheTtl;
        this.fallbackUsdPerEur = fallbackUsdPerEur;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(10_000);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    public double currentUsdPerEur() {
        Instant now = Instant.now();
        CachedRate snapshot = cachedRate;
        if (snapshot != null && now.isBefore(snapshot.expiresAt())) {
            return snapshot.usdPerEur();
        }
        return refreshRate(now);
    }

    private synchronized double refreshRate(Instant now) {
        CachedRate snapshot = cachedRate;
        if (snapshot != null && now.isBefore(snapshot.expiresAt())) {
            return snapshot.usdPerEur();
        }

        try {
            @SuppressWarnings("rawtypes")
            Map response = restClient.get()
                    .uri("/latest?from=EUR&to=USD")
                    .retrieve()
                    .body(Map.class);

            double usdPerEur = extractUsdRate(response);
            cachedRate = new CachedRate(usdPerEur, now.plus(cacheTtl));
            log.info("Cambio EUR/USD aggiornato | 1 EUR = {} USD | cache_ttl={}",
                    formatRate(usdPerEur), cacheTtl);
            return usdPerEur;
        } catch (Exception e) {
            log.warn("Impossibile aggiornare cambio EUR/USD: {}. Uso fallback 1 EUR = {} USD",
                    e.getMessage(), formatRate(fallbackUsdPerEur));
            return fallbackUsdPerEur;
        }
    }

    private static double extractUsdRate(Map<?, ?> response) {
        if (response == null) {
            throw new IllegalStateException("risposta vuota");
        }

        Object ratesObj = response.get("rates");
        if (!(ratesObj instanceof Map<?, ?> rates)) {
            throw new IllegalStateException("campo rates mancante");
        }

        Object usdObj = rates.get("USD");
        if (!(usdObj instanceof Number usd) || usd.doubleValue() <= 0) {
            throw new IllegalStateException("tasso USD mancante o non valido");
        }

        return usd.doubleValue();
    }

    private static String formatRate(double rate) {
        return String.format(Locale.US, "%.4f", rate);
    }

    private record CachedRate(double usdPerEur, Instant expiresAt) {
    }
}
