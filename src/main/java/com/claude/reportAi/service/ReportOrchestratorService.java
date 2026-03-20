package com.claude.reportAi.service;

import com.claude.reportAi.dto.ReportRequest;
import com.claude.reportAi.dto.ReportResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
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

        log.info("Avvio orchestrazione report");
        log.info("Input elaborazione -> prompt='{}', format='{}', allowWebSearch={}",
                safe(userPrompt),
                requestedFormat,
                webSearchAllowed);

        List<Document> retrievedDocuments = vectoreStoreService.searchRelevantDocuments(userPrompt);

        int retrievedDocumentsCount = retrievedDocuments != null ? retrievedDocuments.size() : 0;
        log.info("Ricerca su knowledge base completata -> documenti trovati={}", retrievedDocumentsCount);

        boolean informationFoundInKnowledgeBase = false;
        if (retrievedDocuments != null && !retrievedDocuments.isEmpty()) {
            informationFoundInKnowledgeBase = true;
        }

        log.info("Esito knowledge base -> foundInKnowledgeBase={}", informationFoundInKnowledgeBase);

        List<String> collectedWebResults = List.of();
        if (!informationFoundInKnowledgeBase && webSearchAllowed) {
            log.info("Nessuna informazione utile nel knowledge base. Avvio ricerca web...");
            collectedWebResults = webSearchService.search(userPrompt);
            log.info("Ricerca web completata -> risultati trovati={}",
                    collectedWebResults != null ? collectedWebResults.size() : 0);
        } else if (!informationFoundInKnowledgeBase) {
            log.info("Knowledge base senza risultati utili e ricerca web disabilitata.");
        } else {
            log.info("Uso prioritario del knowledge base interno. Ricerca web non necessaria.");
        }

        String generatedAnswer = reportGenerationService.generateReport(
                userPrompt,
                retrievedDocuments,
                collectedWebResults,
                informationFoundInKnowledgeBase
        );

        log.info("Generazione report completata -> lunghezza risposta={} caratteri",
                generatedAnswer != null ? generatedAnswer.length() : 0);

        boolean webSearchWasUsed = false;
        if (collectedWebResults != null && !collectedWebResults.isEmpty()) {
            webSearchWasUsed = true;
        }

        boolean exportAsCsv = "CSV".equalsIgnoreCase(requestedFormat);
        boolean exportAsXlsx = "XLSX".equalsIgnoreCase(requestedFormat);
        boolean exportAsDocx = "DOCX".equalsIgnoreCase(requestedFormat) || "WORD".equalsIgnoreCase(requestedFormat);

        boolean exportRequired = exportAsCsv || exportAsXlsx || exportAsDocx;

        log.info("Valutazione export -> exportAsCsv={}, exportAsXlsx={}, exportAsDocx={}, exportRequired={}",
                exportAsCsv,
                exportAsXlsx,
                exportAsDocx,
                exportRequired);

        if (exportRequired) {
            String normalizedFormat = exportAsDocx ? "DOCX" : requestedFormat;

            log.info("Avvio esportazione report in formato '{}'", normalizedFormat);

            String exportedFileName = reportExportService.export(generatedAnswer, normalizedFormat);
            String generatedDownloadUrl = "/api/reports/download/" + exportedFileName;

            log.info("Esportazione completata -> fileName='{}', downloadUrl='{}'",
                    exportedFileName,
                    generatedDownloadUrl);

            return ReportResponse.builder()
                    .status("OK")
                    .foundInKnowledgeBase(informationFoundInKnowledgeBase)
                    .webSearchUsed(webSearchWasUsed)
                    .answer(generatedAnswer)
                    .fileName(exportedFileName)
                    .downloadUrl(generatedDownloadUrl)
                    .build();
        }

        log.info("Nessuna esportazione richiesta. Restituisco risposta JSON standard.");

        return ReportResponse.builder()
                .status("OK")
                .foundInKnowledgeBase(informationFoundInKnowledgeBase)
                .webSearchUsed(webSearchWasUsed)
                .answer(generatedAnswer)
                .build();
    }

    private String safe(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }
}