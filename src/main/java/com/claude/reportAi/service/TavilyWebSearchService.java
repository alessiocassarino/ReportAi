package com.claude.reportAi.service;

import com.claude.reportAi.configuration.TavilyProperties;
import com.claude.reportAi.dto.WebSearchResult;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class TavilyWebSearchService implements WebSearchService {

    @Qualifier("tavilyRestClient")
    private final RestClient tavilyRestClient;
    private final TavilyProperties properties;

    @Override
    public List<WebSearchResult> search(String query) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            log.warn("Tavily API key non configurata. Web search saltata.");
            return List.of();
        }

        if (query == null || query.isBlank()) {
            return List.of();
        }

        try {
            TavilySearchRequest body = new TavilySearchRequest(
                    query,
                    properties.isAutoParameters(),
                    properties.getSearchDepth(),
                    properties.getTopic(),
                    properties.getMaxResults(),
                    properties.getIncludeAnswer(),
                    false,
                    true
            );

            TavilySearchResponse response = tavilyRestClient.post()
                    .uri("/search")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(TavilySearchResponse.class);

            if (response == null) {
                return List.of();
            }

            List<WebSearchResult> results = new ArrayList<>();

            if (response.answer() != null && !response.answer().isBlank()) {
                results.add(new WebSearchResult(
                        "Tavily short answer",
                        null,
                        response.answer(),
                        1.0d
                ));
            }

            Optional.ofNullable(response.results())
                    .orElse(List.of())
                    .forEach(item -> results.add(new WebSearchResult(
                            item.title(),
                            item.url(),
                            item.content(),
                            item.score()
                    )));

            log.info("Tavily search completata -> query='{}', risultati={}", safe(query), results.size());
            return results;
        }
        catch (Exception ex) {
            log.error("Errore durante la chiamata a Tavily per query='{}': {}", safe(query), ex.getMessage(), ex);
            return List.of();
        }
    }

    private String safe(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }

    public record TavilySearchRequest(
            String query,
            @JsonProperty("auto_parameters") boolean autoParameters,
            @JsonProperty("search_depth") String searchDepth,
            String topic,
            @JsonProperty("max_results") int maxResults,
            @JsonProperty("include_answer") String includeAnswer,
            @JsonProperty("include_raw_content") boolean includeRawContent,
            @JsonProperty("include_usage") boolean includeUsage
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TavilySearchResponse(
            String query,
            String answer,
            List<TavilyResult> results
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TavilyResult(
            String title,
            String url,
            String content,
            Double score
    ) {
    }
}
