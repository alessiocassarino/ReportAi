package com.claude.reportAi.service;

import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import java.util.List;

/**
 * Modelli supportati per l'analisi contratti.
 * Tutte le chiamate vengono instradate verso Anthropic.
 */
@Component
public class ModelChatClientFactory {

    // -----------------------------------------------------------------------
    // Catalogo modelli supportati
    // -----------------------------------------------------------------------

    public record ModelInfo(String id, String displayName, String provider, String description) {}

    public static final List<ModelInfo> SUPPORTED_MODELS = List.of(
            new ModelInfo("claude-haiku-4-5-20251001",   "Claude Haiku 4.5",     "anthropic", "Più veloce ed economico – default"),
            new ModelInfo("claude-sonnet-4-5",           "Claude Sonnet 4.5",    "anthropic", "Ottimo equilibrio qualità/velocità"),
            new ModelInfo("claude-opus-4-6",             "Claude Opus 4.6",      "anthropic", "Massima qualità Anthropic, più lento")
    );

    public static boolean isAnthropicModel(String model) {
        return model != null && model.startsWith("claude-");
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
    // ChatClient
    // -----------------------------------------------------------------------

    private final ChatClient anthropicClient;

    public ModelChatClientFactory(@Qualifier("anthropicChatClient") ChatClient anthropicClient) {
        this.anthropicClient = anthropicClient;
    }

    /**
     * Esegue una chiamata al modello selezionato con le opzioni appropriate.
     *
     * @param model         ID modello (es. "claude-sonnet-4-5" o "mistral:7b")
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
     * Variante multimodale: allega immagini PNG al messaggio utente.
     * Le immagini vengono passate solo ai modelli Anthropic (Claude ha visione nativa);
     * per Ollama ricade sul metodo testuale base.
     *
     * @param pageImages lista di immagini JPEG come byte[] (es. pagine del PDF)
     */
    public ChatResponse callWithImages(String model, String systemPrompt, String userPrompt,
                                       int maxTokens, boolean useCache, List<byte[]> pageImages) {

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
}
