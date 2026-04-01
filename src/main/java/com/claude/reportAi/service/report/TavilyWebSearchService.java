package com.claude.reportAi.service.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class TavilyWebSearchService implements WebSearchService {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final int maxResults;

    public TavilyWebSearchService(
            @Value("${tavily.api-key}") String apiKey,
            @Value("${tavily.base-url:https://api.tavily.com}") String baseUrl,
            @Value("${tavily.max-results-per-query:5}") int maxResults) {

        this.apiKey = apiKey;
        this.maxResults = maxResults;
        this.objectMapper = new ObjectMapper();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30_000);
        factory.setReadTimeout(30_000);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    @Override
    public List<SearchResult> search(String query) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Tavily API key non configurata. Ricerca web disabilitata per query: {}", query);
            return List.of();
        }

        try {
            Map<String, Object> requestBody = Map.of(
                    "api_key", apiKey,
                    "query", query,
                    "search_depth", "advanced",
                    "max_results", maxResults,
                    "include_answer", false
            );

            String responseBody = restClient.post()
                    .uri("/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            if (responseBody == null || responseBody.isBlank()) {
                log.warn("Tavily ha restituito una risposta vuota per query: {}", query);
                return List.of();
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(responseBody, Map.class);

            Object resultsObj = parsed.get("results");
            if (!(resultsObj instanceof List<?> rawList)) {
                log.warn("Nessun campo 'results' nella risposta Tavily per query: {}", query);
                return List.of();
            }

            List<SearchResult> results = new ArrayList<>();
            for (Object item : rawList) {
                if (item instanceof Map<?, ?> map) {
                    String title   = getStr(map, "title");
                    String url     = getStr(map, "url");
                    String content = getStr(map, "content");
                    results.add(new SearchResult(title, url, content));
                }
            }
            return results;

        } catch (Exception e) {
            log.warn("Errore durante la ricerca Tavily per query '{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    private String getStr(Map<?, ?> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : "";
    }
}
