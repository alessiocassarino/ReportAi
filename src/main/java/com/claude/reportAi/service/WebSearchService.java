package com.claude.reportAi.service;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WebSearchService {

    public List<String> search(String query) {
        // TODO: integra qui un provider reale:
        // Tavily / SerpAPI / Bing Search / Google Custom Search
        return List.of(
                "Risultato web 1 su: " + query,
                "Risultato web 2 su: " + query
        );
    }
}
