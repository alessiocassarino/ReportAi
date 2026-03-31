package com.claude.reportAi.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Verifica che un modello Ollama sia disponibile localmente
 * e lo scarica automaticamente se non presente.
 */
@Service
@Slf4j
public class OllamaModelService {

    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    // Timeout lungo per il pull (modelli da diversi GB)
    private final RestTemplate restTemplate;

    public OllamaModelService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(60 * 60 * 1000); // 1 ora per download modelli grandi
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * Garantisce che il modello sia disponibile localmente.
     * Se non lo è, lo scarica da Ollama Hub.
     */
    public void ensureAvailable(String modelName) {
        if (isAvailable(modelName)) {
            log.info("Modello Ollama '{}' già disponibile localmente", modelName);
            return;
        }

        log.info("Modello Ollama '{}' non trovato localmente. Avvio download...", modelName);
        pull(modelName);
        log.info("Download modello '{}' completato", modelName);
    }

    @SuppressWarnings("unchecked")
    private boolean isAvailable(String modelName) {
        try {
            Map<String, Object> response = restTemplate.getForObject(
                    ollamaBaseUrl + "/api/tags", Map.class);

            if (response == null) return false;

            List<Map<String, Object>> models = (List<Map<String, Object>>) response.get("models");
            if (models == null) return false;

            // Ollama include il tag nel nome (es. "mistral:7b" o "mistral:latest")
            // Normalizziamo aggiungendo ":latest" se non c'è tag
            String normalized = normalizeModelName(modelName);

            return models.stream()
                    .map(m -> (String) m.get("name"))
                    .anyMatch(name -> name != null && (
                            name.equals(modelName) ||
                            name.equals(normalized) ||
                            name.startsWith(modelName + ":")));
        } catch (Exception e) {
            log.error("Impossibile contattare Ollama su {}: {}", ollamaBaseUrl, e.getMessage());
            throw new IllegalStateException(
                    "Ollama non raggiungibile su " + ollamaBaseUrl + ". Assicurati che Ollama sia avviato.");
        }
    }

    private void pull(String modelName) {
        try {
            Map<String, Object> request = Map.of("name", modelName, "stream", false);
            restTemplate.postForObject(ollamaBaseUrl + "/api/pull", request, Map.class);
        } catch (Exception e) {
            log.error("Errore durante il download del modello '{}': {}", modelName, e.getMessage());
            throw new IllegalStateException(
                    "Impossibile scaricare il modello '" + modelName + "': " + e.getMessage());
        }
    }

    private String normalizeModelName(String modelName) {
        return modelName.contains(":") ? modelName : modelName + ":latest";
    }
}
