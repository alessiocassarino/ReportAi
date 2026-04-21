package com.claude.reportAi.service;

import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import java.util.List;

/**
 * Modelli supportati per l'analisi contratti e generazione preventivi.
 * Smista le chiamate al ChatClient corretto (Anthropic o Google Gemini)
 * e costruisce le opzioni appropriate per ciascun provider.
 */
@Component
public class ModelChatClientFactory {

    // -----------------------------------------------------------------------
    // Catalogo modelli supportati
    // -----------------------------------------------------------------------

    public record ModelInfo(String id, String displayName, String provider, String description) {}

    public static final List<ModelInfo> SUPPORTED_MODELS = List.of(
            // Anthropic
            new ModelInfo("claude-haiku-4-5-20251001", "Claude Haiku 4.5",  "anthropic", "Più veloce ed economico – default"),
            new ModelInfo("claude-sonnet-4-5",         "Claude Sonnet 4.5", "anthropic", "Bilanciato – generazione precedente"),
            new ModelInfo("claude-sonnet-4-6",         "Claude Sonnet 4.6", "anthropic", "Ottimo equilibrio qualità/velocità"),
            new ModelInfo("claude-opus-4-6",           "Claude Opus 4.6",   "anthropic", "Alta qualità Anthropic"),
            new ModelInfo("claude-opus-4-7",           "Claude Opus 4.7",   "anthropic", "Massima qualità Anthropic, più lento"),
            // Google Gemini via Vertex AI (GA = Generally Available, Preview = anteprima)
            new ModelInfo("gemini-2.5-pro",                 "gemini-2.5-pro",         "gemini", "Stabile GA – veloce e preciso"),
            new ModelInfo("gemini-2.5-flash",            "gemini-2.5-flash",     "gemini", "Stabile GA – versione leggera, più economica"),
            new ModelInfo("gemini-2.5-flash-lite",   "gemini-2.5-flash-lite","gemini", "Preview – generazione più recente, veloce")
    );

    public static boolean isAnthropicModel(String model) {
        return model != null && model.startsWith("claude-");
    }

    public static boolean isGeminiModel(String model) {
        return model != null && model.startsWith("gemini-");
    }

    public static ModelInfo findModel(String modelId) {
        return SUPPORTED_MODELS.stream()
                .filter(m -> m.id().equals(modelId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Modello non supportato: '" + modelId + "'. " +
                        "Modelli disponibili: " + SUPPORTED_MODELS.stream().map(ModelInfo::id).toList()));
    }

    // -----------------------------------------------------------------------
    // ChatClient routing
    // -----------------------------------------------------------------------

    private final ChatClient anthropicClient;
    private final ChatClient geminiClient;

    public ModelChatClientFactory(
            @Qualifier("anthropicChatClient") ChatClient anthropicClient,
            @Qualifier("geminiChatClient") ChatClient geminiClient) {
        this.anthropicClient = anthropicClient;
        this.geminiClient = geminiClient;
    }

    /**
     * Esegue una chiamata al modello selezionato con le opzioni appropriate.
     *
     * @param model         ID modello (es. "claude-sonnet-4-6" o "gemini-2.5-flash")
     * @param systemPrompt  prompt di sistema
     * @param userPrompt    prompt utente
     * @param maxTokens     numero massimo di token in output
     * @param useCache      abilita il prompt caching Anthropic (ignorato per Ollama)
     */
    public ChatResponse call(String model, String systemPrompt, String userPrompt,
                             int maxTokens, boolean useCache) {
        return callWithImages(model, systemPrompt, userPrompt, maxTokens, useCache, List.of());
    }

    /**
     * Variante multimodale: allega immagini JPEG al messaggio utente.
     * Supportata sia da Anthropic (Claude) che da Google (Gemini).
     *
     * @param pageImages lista di immagini JPEG come byte[] (es. pagine del PDF)
     */
    public ChatResponse callWithImages(String model, String systemPrompt, String userPrompt,
                                       int maxTokens, boolean useCache, List<byte[]> pageImages) {

        if (isGeminiModel(model)) {
            return callGemini(model, systemPrompt, userPrompt, maxTokens, pageImages);
        }

        // Anthropic
        AnthropicChatOptions.Builder opts = AnthropicChatOptions.builder()
                .model(model)
                .maxTokens(maxTokens)
                .temperature(0.1d);

        if (useCache) {
            opts.cacheOptions(AnthropicCacheOptions.builder()
                    .strategy(AnthropicCacheStrategy.SYSTEM_ONLY)
                    .build());
        }

        boolean hasImages = pageImages != null && !pageImages.isEmpty();
        if (hasImages) {
            final List<byte[]> imgs = pageImages;
            return anthropicClient.prompt()
                    .system(systemPrompt)
                    .user(u -> {
                        u.text(userPrompt);
                        imgs.forEach(img ->
                            u.media(MimeTypeUtils.IMAGE_JPEG, new ByteArrayResource(img)));
                    })
                    .options(opts.build())
                    .call()
                    .chatResponse();
        } else {
            return anthropicClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .options(opts.build())
                    .call()
                    .chatResponse();
        }
    }

    private ChatResponse callGemini(String model, String systemPrompt, String userPrompt,
                                    int maxTokens, List<byte[]> pageImages) {
        VertexAiGeminiChatOptions opts = VertexAiGeminiChatOptions.builder()
                .model(model)
                .temperature(0.1D)
                .maxOutputTokens(maxTokens)
                .responseMimeType("application/json")
                .build();

        boolean hasImages = pageImages != null && !pageImages.isEmpty();
        if (hasImages) {
            final List<byte[]> imgs = pageImages;
            return geminiClient.prompt()
                    .system(systemPrompt)
                    .user(u -> {
                        u.text(userPrompt);
                        imgs.forEach(img ->
                            u.media(MimeTypeUtils.IMAGE_JPEG, new ByteArrayResource(img)));
                    })
                    .options(opts)
                    .call()
                    .chatResponse();
        } else {
            return geminiClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .options(opts)
                    .call()
                    .chatResponse();
        }
    }
}
