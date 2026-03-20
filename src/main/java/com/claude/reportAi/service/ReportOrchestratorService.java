package com.claude.reportAi.service;

import com.claude.reportAi.dto.ReportRequest;
import com.claude.reportAi.dto.ReportResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportOrchestratorService {

    private static final String STATUS_OK = "OK";
    private static final String FORMAT_CSV = "CSV";
    private static final String FORMAT_XLSX = "XLSX";
    private static final String FORMAT_DOCX = "DOCX";
    private static final String FORMAT_WORD = "WORD";

    private final VectoreStoreService vectoreStoreService;
    private final WebSearchService webSearchService;
    private final ReportGenerationService reportGenerationService;
    private final ReportExportService reportExportService;

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

        boolean informationFoundInKnowledgeBase = retrievedDocuments != null && !retrievedDocuments.isEmpty();
        log.info("Esito knowledge base -> foundInKnowledgeBase={}", informationFoundInKnowledgeBase);

        List<String> collectedWebResults = resolveWebResults(
                userPrompt,
                webSearchAllowed,
                informationFoundInKnowledgeBase
        );

        String generatedAnswer = reportGenerationService.generateReport(
                userPrompt,
                retrievedDocuments,
                collectedWebResults,
                informationFoundInKnowledgeBase
        );

        log.info("Generazione report completata -> lunghezza risposta={} caratteri",
                generatedAnswer != null ? generatedAnswer.length() : 0);

        boolean webSearchWasUsed = collectedWebResults != null && !collectedWebResults.isEmpty();

        ExportDecision exportDecision = resolveExportDecision(requestedFormat);

        log.info("Valutazione export -> exportAsCsv={}, exportAsXlsx={}, exportAsDocx={}, exportRequired={}",
                exportDecision.exportAsCsv(),
                exportDecision.exportAsXlsx(),
                exportDecision.exportAsDocx(),
                exportDecision.exportRequired());

        if (!exportDecision.exportRequired()) {
            log.info("Nessuna esportazione richiesta. Restituisco risposta JSON standard.");
            return buildResponse(
                    informationFoundInKnowledgeBase,
                    webSearchWasUsed,
                    generatedAnswer,
                    null,
                    null
            );
        }

        log.info("Avvio esportazione report in formato '{}'", exportDecision.normalizedFormat());

        String exportedFileName = reportExportService.export(generatedAnswer, exportDecision.normalizedFormat());
        String generatedDownloadUrl = "/api/reports/download/" + exportedFileName;

        log.info("Esportazione completata -> fileName='{}', downloadUrl='{}'",
                exportedFileName,
                generatedDownloadUrl);

        return buildResponse(
                informationFoundInKnowledgeBase,
                webSearchWasUsed,
                generatedAnswer,
                exportedFileName,
                generatedDownloadUrl
        );
    }

    private List<String> resolveWebResults(String userPrompt,
                                           boolean webSearchAllowed,
                                           boolean informationFoundInKnowledgeBase) {
        if (informationFoundInKnowledgeBase) {
            log.info("Uso prioritario del knowledge base interno. Ricerca web non necessaria.");
            return List.of();
        }

        if (!webSearchAllowed) {
            log.info("Knowledge base senza risultati utili e ricerca web disabilitata.");
            return List.of();
        }

        log.info("Nessuna informazione utile nel knowledge base. Avvio ricerca web...");
        List<String> collectedWebResults = webSearchService.search(userPrompt);
        log.info("Ricerca web completata -> risultati trovati={}",
                collectedWebResults != null ? collectedWebResults.size() : 0);

        return collectedWebResults != null ? collectedWebResults : List.of();
    }

    private ExportDecision resolveExportDecision(String requestedFormat) {
        boolean exportAsCsv = FORMAT_CSV.equalsIgnoreCase(requestedFormat);
        boolean exportAsXlsx = FORMAT_XLSX.equalsIgnoreCase(requestedFormat);
        boolean exportAsDocx = FORMAT_DOCX.equalsIgnoreCase(requestedFormat)
                || FORMAT_WORD.equalsIgnoreCase(requestedFormat);

        boolean exportRequired = exportAsCsv || exportAsXlsx || exportAsDocx;

        String normalizedFormat = requestedFormat;
        if (exportAsDocx) {
            normalizedFormat = FORMAT_DOCX;
        }

        return new ExportDecision(exportAsCsv, exportAsXlsx, exportAsDocx, exportRequired, normalizedFormat);
    }

    private ReportResponse buildResponse(boolean foundInKnowledgeBase,
                                         boolean webSearchUsed,
                                         String answer,
                                         String fileName,
                                         String downloadUrl) {
        return ReportResponse.builder()
                .status(STATUS_OK)
                .foundInKnowledgeBase(foundInKnowledgeBase)
                .webSearchUsed(webSearchUsed)
                .answer(answer)
                .fileName(fileName)
                .downloadUrl(downloadUrl)
                .build();
    }

    private String safe(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }

    private record ExportDecision(
            boolean exportAsCsv,
            boolean exportAsXlsx,
            boolean exportAsDocx,
            boolean exportRequired,
            String normalizedFormat
    ) {}
}