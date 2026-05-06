package com.claude.reportAi.observability;

import com.claude.reportAi.configuration.ModelPricingProperties;
import com.claude.reportAi.service.ExchangeRateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

@Component
@Slf4j
public class TokenUsageLoggingAdvisor implements CallAdvisor {

    // Anthropic prompt caching: cache write costa 1.25x base input, cache read 0.10x.
    // https://docs.anthropic.com/en/docs/build-with-claude/prompt-caching
    private static final double CACHE_WRITE_MULTIPLIER = 1.25;
    private static final double CACHE_READ_MULTIPLIER  = 0.10;

    private final ModelPricingProperties pricing;
    private final ExchangeRateService exchangeRateService;

    public TokenUsageLoggingAdvisor(ModelPricingProperties pricing, ExchangeRateService exchangeRateService) {
        this.pricing = pricing;
        this.exchangeRateService = exchangeRateService;
    }

    @Override
    public String getName() {
        return "tokenUsageLogging";
    }

    @Override
    public int getOrder() {
        return Integer.MAX_VALUE;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.currentTimeMillis();
        ChatClientResponse response = chain.nextCall(request);
        long elapsedMs = System.currentTimeMillis() - start;

        try {
            logUsage(request, response, elapsedMs);
        } catch (Exception e) {
            log.warn("TokenUsageLoggingAdvisor: errore durante il logging dell'usage: {}", e.getMessage());
        }
        return response;
    }

    private void logUsage(ChatClientRequest request, ChatClientResponse response, long elapsedMs) {
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null || chatResponse.getMetadata() == null) {
            log.info("AI usage | metadata non disponibile | elapsed={}ms", elapsedMs);
            return;
        }

        Usage usage = chatResponse.getMetadata().getUsage();
        if (usage == null) {
            log.info("AI usage | usage non disponibile | elapsed={}ms", elapsedMs);
            return;
        }

        String model = resolveModel(request, chatResponse);
        long inputTokens    = nz(usage.getPromptTokens());
        long outputTokens   = nz(usage.getCompletionTokens());
        long cacheRead      = extractNativeLong(usage, "cacheReadInputTokens");
        long cacheCreation  = extractNativeLong(usage, "cacheCreationInputTokens");

        ModelPricingProperties.ModelPrice price = pricing != null ? pricing.priceFor(model) : null;
        double totalUsd = 0.0;
        if (price != null) {
            double inputUsd         = (inputTokens    / 1_000_000.0) * price.input();
            double outputUsd        = (outputTokens   / 1_000_000.0) * price.output();
            double cacheReadUsd     = (cacheRead      / 1_000_000.0) * price.input() * CACHE_READ_MULTIPLIER;
            double cacheCreationUsd = (cacheCreation  / 1_000_000.0) * price.input() * CACHE_WRITE_MULTIPLIER;
            totalUsd = inputUsd + outputUsd + cacheReadUsd + cacheCreationUsd;
        } else {
            log.warn("Prezzi non configurati per modello '{}': costo non calcolato. "
                   + "Aggiungi app.pricing.models.{}.input/.output in application.properties", model, model);
        }
        double usdPerEur = exchangeRateService.currentUsdPerEur();
        double totalEur = totalUsd / usdPerEur;

        log.info("AI usage | model={} | input={} | output={} | cache_read={} | cache_write={} | cost=${} (EUR {}) | eur_usd={} | elapsed={}ms",
                model, inputTokens, outputTokens, cacheRead, cacheCreation,
                fmt(totalUsd), fmt(totalEur), fmt(usdPerEur), elapsedMs);
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private static long nz(Number v) {
        return v == null ? 0L : v.longValue();
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.4f", v);
    }

    private static String resolveModel(ChatClientRequest request, ChatResponse response) {
        try {
            ChatOptions opts = request.prompt().getOptions();
            if (opts != null && opts.getModel() != null && !opts.getModel().isBlank()) {
                return opts.getModel();
            }
        } catch (Exception ignored) {}
        try {
            String m = response.getMetadata().getModel();
            if (m != null && !m.isBlank()) return m;
        } catch (Exception ignored) {}
        return "unknown";
    }

    // Estrae un campo numerico dal nativeUsage del provider, gestendo sia record-style
    // (es. cacheReadInputTokens()) sia bean-style (getCacheReadInputTokens()) sia Map.
    // Restituisce 0 se il campo non esiste — i provider che non usano caching non lo espongono.
    private static long extractNativeLong(Usage usage, String fieldName) {
        Object nativeUsage;
        try {
            nativeUsage = usage.getNativeUsage();
        } catch (Exception e) {
            return 0L;
        }
        if (nativeUsage == null) return 0L;

        if (nativeUsage instanceof Map<?, ?> map) {
            Object v = map.get(fieldName);
            if (v instanceof Number n) return n.longValue();
            return 0L;
        }

        Long v = invokeLongAccessor(nativeUsage, fieldName);
        if (v != null) return v;

        String getter = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        v = invokeLongAccessor(nativeUsage, getter);
        return v != null ? v : 0L;
    }

    private static Long invokeLongAccessor(Object obj, String methodName) {
        try {
            Object v = obj.getClass().getMethod(methodName).invoke(obj);
            if (v instanceof Number n) return n.longValue();
        } catch (Exception ignored) {}
        return null;
    }
}
