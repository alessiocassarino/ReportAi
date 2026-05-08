package com.claude.reportAi.service.estimate;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class TavilyWebSearchService implements WebSearchService {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final int maxResults;
    private final List<String> denylistDomains;

    public TavilyWebSearchService(
            @Value("${tavily.api-key}") String apiKey,
            @Value("${tavily.base-url:https://api.tavily.com}") String baseUrl,
            @Value("${tavily.max-results-per-query:5}") int maxResults,
            @Value("${tavily.denylist-domains:huggingface.co,reddit.com,quora.com,stackoverflow.com,youtube.com,github.com,arxiv.org,medium.com,wikipedia.org,twitter.com,x.com,linkedin.com,facebook.com,instagram.com,tiktok.com}") List<String> denylistDomains) {

        this.apiKey = apiKey;
        this.maxResults = maxResults;
        this.denylistDomains = denylistDomains;
        this.objectMapper = new ObjectMapper();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30_000);
        factory.setReadTimeout(30_000);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();

        log.info("TavilyWebSearchService inizializzato: maxResults={}, denylist={} domini",
                maxResults, denylistDomains.size());
    }

    @Override
    public List<SearchResult> search(String query) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Tavily API key non configurata. Ricerca web disabilitata per query: {}", query);
            return List.of();
        }

        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("api_key", apiKey);
            requestBody.put("query", query);
            requestBody.put("search_depth", "advanced");
            requestBody.put("max_results", maxResults);
            requestBody.put("include_answer", false);
            if (!denylistDomains.isEmpty()) {
                requestBody.put("exclude_domains", denylistDomains);
            }

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

            List<SearchResult> allResults = new ArrayList<>();
            for (Object item : rawList) {
                if (item instanceof Map<?, ?> map) {
                    String title   = getStr(map, "title");
                    String url     = getStr(map, "url");
                    String content = getStr(map, "content");
                    allResults.add(new SearchResult(title, url, content));
                }
            }

            // Post-filter: safety net to catch any blocked domains that slipped through the API-level exclusion
            List<SearchResult> filtered = allResults.stream()
                    .filter(r -> !isDomainBlocked(r.url()))
                    .toList();

            int blocked = allResults.size() - filtered.size();
            if (blocked > 0) {
                log.info("Web search post-filter: {} risultati scartati per dominio bloccato | query='{}'",
                        blocked, query);
            }
            return filtered;

        } catch (Exception e) {
            log.warn("Errore durante la ricerca Tavily per query '{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    private boolean isDomainBlocked(String url) {
        if (url == null || url.isBlank()) return false;
        String lowerUrl = url.toLowerCase();
        return denylistDomains.stream().anyMatch(domain -> lowerUrl.contains(domain.toLowerCase()));
    }

    private String getStr(Map<?, ?> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : "";
    }
}
