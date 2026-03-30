package com.claude.reportAi.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
@Slf4j
public class AnthropicFileCleanupClient {

    private static final String FILES_API_URL = "https://api.anthropic.com/v1/files/";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String BETA_HEADER = "files-api-2025-04-14";

    @Value("${spring.ai.anthropic.api-key}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    public boolean deleteFileFromAnthropic(String fileId) {
        try {
            HttpHeaders headers = buildHeaders();
            HttpEntity<Void> request = new HttpEntity<>(headers);

            ResponseEntity<Void> response = restTemplate.exchange(
                    FILES_API_URL + fileId,
                    HttpMethod.DELETE,
                    request,
                    Void.class
            );

            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.warn("Errore eliminazione file Anthropic (file_id={}): {}", fileId, e.getMessage());
            return false;
        }
    }

    public byte[] downloadFileFromAnthropic(String fileId) {
        HttpHeaders headers = buildHeaders();
        headers.set("Accept", "application/octet-stream");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<byte[]> response = restTemplate.exchange(
                FILES_API_URL + fileId + "/content",
                HttpMethod.GET,
                request,
                byte[].class
        );

        return response.getBody();
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", ANTHROPIC_VERSION);
        headers.set("anthropic-beta", BETA_HEADER);
        return headers;
    }
}