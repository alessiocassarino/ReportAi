package com.claude.reportAi.utils;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class AnthropicFileCleanupClient {

    private final RestTemplate restTemplate = new RestTemplate();
    private final String apiKey;

    public AnthropicFileCleanupClient(
            @org.springframework.beans.factory.annotation.Value("${spring.ai.anthropic.api-key}")
            String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * Cancella un file dai server Anthropic tramite DELETE API.
     *
     * DELETE https://api.anthropic.com/v1/files/{file_id}
     * Headers:
     *   x-api-key: YOUR_KEY
     *   anthropic-version: 2023-06-01
     *   anthropic-beta: files-api-2025-04-14
     */
    public boolean deleteFileFromAnthropic(String fileId) {
        String url = "https://api.anthropic.com/v1/files/" + fileId;

        HttpHeaders headers = new HttpHeaders();
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");
        headers.set("anthropic-beta", "files-api-2025-04-14");

        HttpEntity<Void> request = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.DELETE, request, String.class
            );
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            // Logga ma non blocca — il file scadrà comunque in 24h
            System.err.println("Errore cancellazione file Anthropic: " + e.getMessage());
            return false;
        }
    }

    /**
     * Scarica un file da Anthropic (se anthropicApi.downloadFile() non funziona).
     *
     * GET https://api.anthropic.com/v1/files/{file_id}/content
     */
    public byte[] downloadFileFromAnthropic(String fileId) {
        String url = "https://api.anthropic.com/v1/files/" + fileId + "/content";

        HttpHeaders headers = new HttpHeaders();
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");
        headers.set("anthropic-beta", "files-api-2025-04-14");

        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<byte[]> response = restTemplate.exchange(
                url, HttpMethod.GET, request, byte[].class
        );

        return response.getBody();
    }
}
