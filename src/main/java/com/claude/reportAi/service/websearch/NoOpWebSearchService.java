package com.claude.reportAi.service.websearch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.web-search.provider", havingValue = "NONE", matchIfMissing = true)
public class NoOpWebSearchService implements WebSearchProvider {

    @Override
    public List<String> search(String query) {
        log.debug("Web search disabled (NoOpWebSearchService) -> query={}", safe(query));
        return List.of();
    }

    private String safe(String text) {
        return text != null && text.length() > 100 ? text.substring(0, 100) + "..." : text;
    }
}
