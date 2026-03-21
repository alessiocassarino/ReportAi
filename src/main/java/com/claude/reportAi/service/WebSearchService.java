package com.claude.reportAi.service;

import com.claude.reportAi.service.websearch.WebSearchProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebSearchService {

    private final WebSearchProvider webSearchProvider;

    public List<String> search(String query) {
        if (webSearchProvider == null) {
            log.debug("Web search provider not available");
            return List.of();
        }
        
        try {
            log.info("Executing web search -> query={}", safe(query));
            List<String> results = webSearchProvider.search(query);
            log.info("Web search completed -> results found={}", results.size());
            return results;
        } catch (Exception e) {
            log.error("Web search error: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private String safe(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() > 100 ? text.substring(0, 100) + "..." : text;
    }
}
