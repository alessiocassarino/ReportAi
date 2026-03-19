package com.claude.reportAi.service;

import com.claude.reportAi.dto.ReportRequest;
import com.claude.reportAi.dto.ReportResponse;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ReportOrchestratorService {

    private final VectoreStoreService vectoreStoreService;
    private final WebSearchService webSearchService;
    private final ReportGenerationService reportGenerationService;
    private final ReportExportService reportExportService;

    public ReportOrchestratorService(VectoreStoreService vectoreStoreService,
                                     WebSearchService webSearchService,
                                     ReportGenerationService reportGenerationService,
                                     ReportExportService reportExportService) {
        this.vectoreStoreService = vectoreStoreService;
        this.webSearchService = webSearchService;
        this.reportGenerationService = reportGenerationService;
        this.reportExportService = reportExportService;
    }

    @Transactional(readOnly = true)
    public ReportResponse generate(ReportRequest request) {
        String userPrompt = request.getPrompt();
        String requestedFormat = request.getFormat();
        boolean webSearchAllowed = request.isAllowWebSearch();

        List<Document> retrievedDocuments = vectoreStoreService.searchRelevantDocuments(userPrompt);

        boolean informationFoundInKnowledgeBase = false;
        if (retrievedDocuments != null && !retrievedDocuments.isEmpty()) {
            informationFoundInKnowledgeBase = true;
        }

        List<String> collectedWebResults = List.of();
        if (!informationFoundInKnowledgeBase && webSearchAllowed) {
            collectedWebResults = webSearchService.search(userPrompt);
        }

        String generatedAnswer = reportGenerationService.generateReport(
                userPrompt,
                retrievedDocuments,
                collectedWebResults,
                informationFoundInKnowledgeBase
        );

        boolean webSearchWasUsed = false;
        if (collectedWebResults != null && !collectedWebResults.isEmpty()) {
            webSearchWasUsed = true;
        }

        boolean exportAsCsv = false;
        if ("CSV".equalsIgnoreCase(requestedFormat)) {
            exportAsCsv = true;
        }

        boolean exportAsXlsx = false;
        if ("XLSX".equalsIgnoreCase(requestedFormat)) {
            exportAsXlsx = true;
        }

        boolean exportRequired = false;
        if (exportAsCsv || exportAsXlsx) {
            exportRequired = true;
        }

        if (exportRequired) {
            String exportedFileName = reportExportService.export(generatedAnswer, requestedFormat);
            String generatedDownloadUrl = "/api/reports/download/" + exportedFileName;

            return ReportResponse.builder()
                    .status("OK")
                    .foundInKnowledgeBase(informationFoundInKnowledgeBase)
                    .webSearchUsed(webSearchWasUsed)
                    .answer(generatedAnswer)
                    .fileName(exportedFileName)
                    .downloadUrl(generatedDownloadUrl)
                    .build();
        }

        return ReportResponse.builder()
                .status("OK")
                .foundInKnowledgeBase(informationFoundInKnowledgeBase)
                .webSearchUsed(webSearchWasUsed)
                .answer(generatedAnswer)
                .build();
    }
}