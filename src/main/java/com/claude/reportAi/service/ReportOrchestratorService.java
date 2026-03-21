package com.claude.reportAi.service;

import com.claude.reportAi.dto.*;
import com.claude.reportAi.entities.ReportTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportOrchestratorService {

    private static final String STATUS_OK = "OK";
    private static final String FORMAT_CSV = "CSV";
    private static final String FORMAT_XLSX = "XLSX";
    private static final String FORMAT_DOCX = "DOCX";
    private static final String FORMAT_WORD = "WORD";
    private static final String FORMAT_JSON = "JSON";

    private final VectoreStoreService vectoreStoreService;
    private final WebSearchService webSearchService;
    private final ReportGenerationService reportGenerationService;
    private final ReportExportService reportExportService;
    private final TemporaryFileService temporaryFileService;
    private final AttachmentProcessingService attachmentProcessingService;
    private final TemplateService templateService;
    private final TemplateRendererService templateRendererService;
    private final ContextAssemblyService contextAssemblyService;
    private final OutputValidationService outputValidationService;
    private final ContextQualityService contextQualityService;

    @Transactional
    public ReportResponse generate(ReportRequest request,
                                   List<MultipartFile> files,
                                   GenerateAttachmentsRequest attachmentsRequest) {

        long startTime = System.currentTimeMillis();
        Path requestTempDir = null;

        try {
            String userPrompt = request.getPrompt();
            String requestedFormat = request.getFormat();
            boolean webSearchAllowed = request.isAllowWebSearch();
            Integer systemPromptId = request.getSystemPromptId();

            log.info("🚀 Avvio orchestrazione report multipart");
            log.info("📋 Input elaborazione -> prompt='{}', format='{}', allowWebSearch={}, filesCount={}",
                    safe(userPrompt),
                    requestedFormat,
                    webSearchAllowed,
                    files != null ? files.size() : 0);

            requestTempDir = temporaryFileService.createRequestTempDirectory();

            List<ProcessedAttachment> processedAttachments = attachmentProcessingService.processAttachments(
                    requestTempDir,
                    files,
                    attachmentsRequest != null ? attachmentsRequest.getAttachments() : List.of()
            );

            ReportTemplate selectedTemplate = resolveSelectedTemplate(request, processedAttachments);

            List<Document> retrievedDocuments = vectoreStoreService.searchRelevantDocuments(userPrompt);
            boolean informationFoundInKnowledgeBase = retrievedDocuments != null && !retrievedDocuments.isEmpty();

            List<String> collectedWebResults = resolveWebResults(
                    userPrompt,
                    webSearchAllowed,
                    informationFoundInKnowledgeBase
            );

            AssembledContext assembledContext = contextAssemblyService.assemble(
                    retrievedDocuments,
                    processedAttachments,
                    collectedWebResults,
                    selectedTemplate
            );

            // NEW: Score context quality
            ContextQualityMetrics contextQualityMetrics = contextQualityService.scoreContext(
                    assembledContext,
                    userPrompt
            );
            log.info("📊 Context quality scored -> overallScore={}", 
                    String.format("%.2f", contextQualityMetrics.getOverallScore()));

            String generatedAnswer = reportGenerationService.generateReport(
                    userPrompt,
                    assembledContext,
                    informationFoundInKnowledgeBase,
                    systemPromptId
            );

            // NEW: Validate output
            OutputValidationResult validationResult = outputValidationService.validate(
                    generatedAnswer,
                    informationFoundInKnowledgeBase
            );

            boolean webSearchWasUsed = collectedWebResults != null && !collectedWebResults.isEmpty();

            // ✅ FIXED LOGIC: Check format FIRST, then decide rendering strategy
            ExportDecision exportDecision = resolveExportDecision(requestedFormat);

            // Case 1: Template + Rendering
            if (selectedTemplate != null) {
                log.info("🎨 Rendering con template -> templateId={}, type={}", 
                        selectedTemplate.getId(), selectedTemplate.getTemplateType());
                
                RenderedOutput renderedOutput = templateRendererService.render(
                        selectedTemplate,
                        generatedAnswer,
                        request
                );

                long executionTime = System.currentTimeMillis() - startTime;
                ResponseMetadata metadata = buildMetadata(
                        executionTime,
                        validationResult,
                        contextQualityMetrics,
                        informationFoundInKnowledgeBase,
                        retrievedDocuments
                );

                log.info("✅ Report generato con template -> fileName={}", renderedOutput.getFileName());

                return buildResponse(
                        informationFoundInKnowledgeBase,
                        webSearchWasUsed,
                        generatedAnswer,
                        renderedOutput.getFileName(),
                        renderedOutput.getDownloadUrl(),
                        metadata
                );
            }

            // Case 2: No Template, But Export Required (XLSX, CSV, DOCX)
            if (exportDecision.exportRequired()) {
                log.info("📤 Esportazione SENZA template -> format={}", exportDecision.normalizedFormat());
                
                String exportedFileName = reportExportService.export(generatedAnswer, exportDecision.normalizedFormat());
                String generatedDownloadUrl = "/api/reports/download/" + exportedFileName;

                long executionTime = System.currentTimeMillis() - startTime;
                ResponseMetadata metadata = buildMetadata(
                        executionTime,
                        validationResult,
                        contextQualityMetrics,
                        informationFoundInKnowledgeBase,
                        retrievedDocuments
                );

                log.info("✅ Report esportato -> fileName={}, format={}", 
                        exportedFileName, exportDecision.normalizedFormat());

                return buildResponse(
                        informationFoundInKnowledgeBase,
                        webSearchWasUsed,
                        generatedAnswer,
                        exportedFileName,
                        generatedDownloadUrl,
                        metadata
                );
            }

            // Case 3: JSON Format (No Export, Just Response)
            log.info("📝 Report in formato JSON (nessun file)");
            long executionTime = System.currentTimeMillis() - startTime;
            ResponseMetadata metadata = buildMetadata(
                    executionTime,
                    validationResult,
                    contextQualityMetrics,
                    informationFoundInKnowledgeBase,
                    retrievedDocuments
            );

            return buildResponse(
                    informationFoundInKnowledgeBase,
                    webSearchWasUsed,
                    generatedAnswer,
                    null,
                    null,
                    metadata
            );

        } finally {
            temporaryFileService.cleanupDirectory(requestTempDir);
        }
    }

    private ReportTemplate resolveSelectedTemplate(ReportRequest request,
                                                   List<ProcessedAttachment> processedAttachments) {
        // 1. Check if template was provided in this request (ATTACHMENTS)
        if (processedAttachments != null && !processedAttachments.isEmpty()) {
            Optional<Long> uploadedTemplateId = processedAttachments.stream()
                    .filter(ProcessedAttachment::isTemplate)
                    .map(ProcessedAttachment::getSavedTemplateId)
                    .filter(id -> id != null)
                    .findFirst();

            if (uploadedTemplateId.isPresent()) {
                log.info("✅ Template trovato negli attachments -> id={}", uploadedTemplateId.get());
                Optional<ReportTemplate> template = templateService.findById(uploadedTemplateId.get());
                if (template.isPresent()) {
                    log.info("✅ Template SALVATO nel DB per riutilizzo futuro -> templateId={}, type={}",
                            template.get().getId(), template.get().getTemplateType());
                    return template.get();
                }
            }
        }

        // 2. Check if explicit template ID was specified in request
        if (request.getSelectedTemplateId() != null) {
            log.info("✅ Template trovato nel request (selectedTemplateId) -> id={}", request.getSelectedTemplateId());
            Optional<ReportTemplate> template = templateService.findById(request.getSelectedTemplateId());
            if (template.isPresent()) {
                log.info("✅ Template RIUTILIZZATO dal DB -> templateId={}, type={}",
                        template.get().getId(), template.get().getTemplateType());
                return template.get();
            }
        }

        // 3. NEW: If format is XLSX/DOCX and template exists in attachments, force its use
        String format = request.getFormat() != null ? request.getFormat().toUpperCase() : FORMAT_JSON;
        if ((FORMAT_XLSX.equals(format) || FORMAT_DOCX.equals(format)) && processedAttachments != null && !processedAttachments.isEmpty()) {
            Optional<Long> templateForFormat = processedAttachments.stream()
                    .filter(ProcessedAttachment::isTemplate)
                    .map(ProcessedAttachment::getSavedTemplateId)
                    .filter(id -> id != null)
                    .findFirst();

            if (templateForFormat.isPresent()) {
                Optional<ReportTemplate> template = templateService.findById(templateForFormat.get());
                if (template.isPresent()) {
                    log.info("✅ Template AUTO-DETECTED per format {} -> templateId={}, type={}",
                            format, template.get().getId(), template.get().getTemplateType());
                    return template.get();
                }
            }
        }

        log.debug("⚠️ Nessun template disponibile - report sarà esportato in formato richiesto");
        return null;
    }

    private List<String> resolveWebResults(String userPrompt,
                                           boolean webSearchAllowed,
                                           boolean informationFoundInKnowledgeBase) {
        if (informationFoundInKnowledgeBase) {
            log.info("✅ Uso prioritario del knowledge base interno. Ricerca web non necessaria.");
            return List.of();
        }

        if (!webSearchAllowed) {
            log.info("⚠️ Knowledge base senza risultati utili e ricerca web disabilitata.");
            return List.of();
        }

        log.info("🌐 Nessuna informazione utile nel knowledge base. Avvio ricerca web...");
        List<String> collectedWebResults = webSearchService.search(userPrompt);
        log.info("🌐 Ricerca web completata -> risultati trovati={}",
                collectedWebResults != null ? collectedWebResults.size() : 0);

        return collectedWebResults != null ? collectedWebResults : List.of();
    }

    private ExportDecision resolveExportDecision(String requestedFormat) {
        if (requestedFormat == null || requestedFormat.isBlank()) {
            // Default to JSON if not specified
            return new ExportDecision(false, false, false, false, FORMAT_JSON);
        }

        String upperFormat = requestedFormat.toUpperCase();
        
        boolean exportAsCsv = FORMAT_CSV.equalsIgnoreCase(upperFormat);
        boolean exportAsXlsx = FORMAT_XLSX.equalsIgnoreCase(upperFormat);
        boolean exportAsDocx = FORMAT_DOCX.equalsIgnoreCase(upperFormat)
                || FORMAT_WORD.equalsIgnoreCase(upperFormat);

        boolean exportRequired = exportAsCsv || exportAsXlsx || exportAsDocx;

        String normalizedFormat = upperFormat;
        if (exportAsDocx) {
            normalizedFormat = FORMAT_DOCX;
        }

        return new ExportDecision(exportAsCsv, exportAsXlsx, exportAsDocx, exportRequired, normalizedFormat);
    }

    private ResponseMetadata buildMetadata(long executionTimeMs,
                                          OutputValidationResult validationResult,
                                          ContextQualityMetrics contextQualityMetrics,
                                          boolean foundInKnowledgeBase,
                                          List<Document> retrievedDocuments) {
        return ResponseMetadata.builder()
                .executionTimeMs(executionTimeMs)
                .outputQualityScore(validationResult.getQualityScore())
                .contextQualityScore(contextQualityMetrics.getOverallScore())
                .knowledgeBaseDocumentsUsed(foundInKnowledgeBase ? retrievedDocuments.size() : 0)
                .similarityAverage(contextQualityMetrics.getAverageSimilarity())
                .generationModel("claude-sonnet-4-6")
                .generatedAt(LocalDateTime.now())
                .halluccinationRiskDetected(validationResult.isCritical())
                .validationMessage(validationResult.getValidationMessage())
                .build();
    }

    private ReportResponse buildResponse(boolean foundInKnowledgeBase,
                                         boolean webSearchUsed,
                                         String answer,
                                         String fileName,
                                         String downloadUrl,
                                         ResponseMetadata metadata) {
        return ReportResponse.builder()
                .status(STATUS_OK)
                .foundInKnowledgeBase(foundInKnowledgeBase)
                .webSearchUsed(webSearchUsed)
                .answer(answer)
                .fileName(fileName)
                .downloadUrl(downloadUrl)
                .metadata(metadata)
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
    ) {
    }
}
