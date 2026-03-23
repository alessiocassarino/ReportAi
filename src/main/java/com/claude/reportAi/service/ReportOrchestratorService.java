package com.claude.reportAi.service;

import com.claude.reportAi.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReportOrchestratorService {

    private final ReportPlannerService reportPlannerService;
    private final VectoreStoreService vectoreStoreService;
    private final WebSearchService webSearchService;
    private final ReportGenerationService reportGenerationService;

    public ReportResponse generate(ReportRequest request) {
        log.info("Avvio orchestrazione report -> prompt='{}', format='{}', allowWebSearch={}",
                safe(request.getPrompt()),
                request.getFormat(),
                request.isAllowWebSearch());

        ReportPlan plan = reportPlannerService.plan(request);

        List<Document> retrievedDocuments = vectoreStoreService.searchRelevantDocuments(
                plan.retrievalQuery(),
                request.getKnowledgeFilterExpression(),
                request.getTopK(),
                request.getSimilarityThreshold()
        );

        boolean foundInKnowledgeBase = retrievedDocuments != null && !retrievedDocuments.isEmpty();

        boolean shouldUseWebSearch = request.isAllowWebSearch()
                && (!foundInKnowledgeBase || plan.webSearchRecommended());

        List<WebSearchResult> webResults = shouldUseWebSearch
                ? webSearchService.search(plan.retrievalQuery())
                : List.of();

        ReportGenerationResult result = reportGenerationService.generate(
                request.getPrompt(),
                plan,
                retrievedDocuments,
                webResults
        );

        return ReportResponse.builder()
                .status("OK")
                .foundInKnowledgeBase(foundInKnowledgeBase)
                .webSearchUsed(webResults != null && !webResults.isEmpty())
                .resolvedFormat(result.resolvedFormat().name())
                .answer(result.answer())
                .fileName(result.fileName())
                .downloadUrl(result.downloadUrl())
                .build();
    }

    private String safe(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }
}