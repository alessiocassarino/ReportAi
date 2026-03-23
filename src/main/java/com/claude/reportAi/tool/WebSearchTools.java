package com.claude.reportAi.tool;

import com.claude.reportAi.dto.WebSearchResult;
import com.claude.reportAi.service.WebSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class WebSearchTools {

    private final WebSearchService webSearchService;

    @Tool(
            name = "web_search",
            description = "Esegue una ricerca web aggiornata e restituisce risultati rilevanti con titolo, url, contenuto e score."
    )
    public List<WebSearchResult> webSearch(
            @ToolParam(description = "La query di ricerca web da eseguire")
            String query
    ) {
        return webSearchService.search(query);
    }
}