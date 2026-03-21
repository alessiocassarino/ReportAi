package com.claude.reportAi.service.websearch;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
@ConditionalOnProperty(name = "app.web-search.provider", havingValue = "TAVILY")
public class TavilyWebSearchService implements WebSearchProvider {

    private final RestTemplate restTemplate;
    private final String tavilyApiKey;
    private final int cacheTtlMinutes;
    private final Cache<String, List<String>> resultCache;

    public TavilyWebSearchService(RestTemplate restTemplate,
                                  @Value("${app.web-search.tavily.api-key:}") String tavilyApiKey,
                                  @Value("${app.web-search.cache-ttl-minutes:1440}") int cacheTtlMinutes) {
        this.restTemplate = restTemplate;
        this.tavilyApiKey = tavilyApiKey;
        this.cacheTtlMinutes = cacheTtlMinutes > 0 ? cacheTtlMinutes : 1440;

        this.resultCache = CacheBuilder.newBuilder()
                .expireAfterWrite(this.cacheTtlMinutes, TimeUnit.MINUTES)
                .maximumSize(1000)
                .recordStats()
                .build();

        log.info("TavilyWebSearchService initialized -> cacheTTL={}min, apiKeyConfigured={}",
                this.cacheTtlMinutes, !tavilyApiKey.isEmpty());
    }

    @Override
    public List<String> search(String query) {
        if (query == null || query.isBlank()) {
            log.warn("Empty query provided for web search");
            return List.of();
        }

        log.info("Starting Tavily web search -> query={}", safe(query));

        // 1. Check cache
        List<String> cachedResults = resultCache.getIfPresent(query);
        if (cachedResults != null) {
            log.info("Cache hit for query -> size={}", cachedResults.size());
            return cachedResults;
        }

        try {
            // 2. Validate API key
            if (tavilyApiKey == null || tavilyApiKey.isEmpty()) {
                log.error("Tavily API key not configured");
                return List.of();
            }

            // 3. Build request
            TavilyRequest request = TavilyRequest.builder()
                    .query(query)
                    .api_key(tavilyApiKey)
                    .max_results(5)
                    .search_depth("advanced")
                    .include_domains(true)
                    .build();

            log.debug("Tavily API request -> query={}", safe(query));

            HttpEntity<TavilyRequest> entity = new HttpEntity<>(request);

            // 4. Call API
            ResponseEntity<TavilyResponse> response = restTemplate.postForEntity(
                    "https://api.tavily.com/search",
                    entity,
                    TavilyResponse.class
            );

            // 5. Handle response
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.error("Tavily API error -> statusCode={}", response.getStatusCode());
                return List.of();
            }

            TavilyResponse tavilyResponse = response.getBody();

            if (tavilyResponse.getResults() == null || tavilyResponse.getResults().isEmpty()) {
                log.info("Tavily search returned no results");
                return List.of();
            }

            // 6. Format results
            List<String> results = tavilyResponse.getResults().stream()
                    .map(this::formatSearchResult)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            // 7. Cache results
            resultCache.put(query, results);

            log.info("Tavily web search completed -> results={}, cacheHits={}, cacheMisses={}",
                    results.size(),
                    resultCache.stats().hitCount(),
                    resultCache.stats().missCount());

            return results;

        } catch (RestClientException e) {
            log.error("Tavily API connection error: {}", e.getMessage());
            return List.of();
        } catch (Exception e) {
            log.error("Tavily web search error: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private String formatSearchResult(TavilyResult result) {
        try {
            String title = result.getTitle() != null ? result.getTitle() : "Untitled";
            String url = result.getUrl() != null ? result.getUrl() : "Unknown";
            String content = result.getContent() != null ? result.getContent() : "";
            
            // Truncate content if too long
            if (content.length() > 500) {
                content = content.substring(0, 500) + "...";
            }

            return String.format(
                    "**%s** (Score: %.2f)%n%s%n%nSource: %s",
                    title,
                    result.getScore(),
                    content,
                    url
            );
        } catch (Exception e) {
            log.warn("Error formatting search result: {}", e.getMessage());
            return null;
        }
    }

    private String safe(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() > 100 ? text.substring(0, 100) + "..." : text;
    }
}
