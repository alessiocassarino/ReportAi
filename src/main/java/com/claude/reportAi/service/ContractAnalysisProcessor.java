package com.claude.reportAi.service;

import com.claude.reportAi.entities.ContractAnalysis;
import com.claude.reportAi.exception.JobCancelledException;
import com.claude.reportAi.repository.ContractAnalysisRepository;
import com.claude.reportAi.service.ContractSectionExtractor.ContractSection;
import com.claude.reportAi.service.PromptTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Async processor that runs the full Map-Reduce pipeline for contract risk analysis.
 * Each section is analyzed independently (map), then results are aggregated
 * into a final DOCX report (reduce).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ContractAnalysisProcessor {

    private final ContractAnalysisRepository contractAnalysisRepository;
    private final ContractSectionExtractor sectionExtractor;
    private final TokenRateLimiter rateLimiter;
    private final ModelChatClientFactory modelFactory;
    private final ContractReportBuilder reportBuilder;
    private final PromptTemplateService promptTemplateService;

    // -----------------------------------------------------------------------
    // Entry point (called by ContractAnalysisService)
    // -----------------------------------------------------------------------

    @Async("contractAnalysisExecutor")
    public void processAsync(UUID jobId, byte[] pdfBytes, String originalFilename, String model) {
        log.info("START analisi contratto | jobId={} | file={} | model={}", jobId, originalFilename, model);
        long startTime = System.currentTimeMillis();

        try {
            String sector = loadJob(jobId).getSector();
            final String sectionSystemPrompt = promptTemplateService.resolve("contract-risk-section-analysis", sector);
            final String synthesisSystemPrompt = promptTemplateService.resolve("contract-risk-synthesis", sector);
            final String sectionUserTemplate = promptTemplateService.resolve("contract-risk-section-user", sector);
            final String synthesisUserTemplate = promptTemplateService.resolve("contract-risk-synthesis-user", sector);

            // Step 1 – Extract text
            updateJob(jobId, ContractAnalysis.JobStatus.PROCESSING, 5, "Estrazione testo dal PDF");
            String fullText = extractTextFromPdf(pdfBytes);

            if (fullText.isBlank()) {
                throw new IllegalStateException("Impossibile estrarre testo dal PDF. Verificare che il file non sia scansionato.");
            }
            log.info("Testo estratto: {} caratteri", fullText.length());

            // Step 2 – Split into sections
            updateProgress(jobId, 10, "Identificazione sezioni del contratto");
            List<ContractSection> sections = sectionExtractor.extractSections(fullText);
            log.info("Sezioni identificate: {}", sections.size());

            // Step 3 – MAP: analyze each section
            List<String> sectionResults = new ArrayList<>();
            int totalSections = sections.size();

            for (int i = 0; i < totalSections; i++) {
                throwIfCancelled(jobId);
                ContractSection section = sections.get(i);
                int progress = 10 + (int) ((i / (double) totalSections) * 70); // 10% → 80%

                updateProgress(jobId, progress, "Analisi sezione (" + (i + 1) + "/" + totalSections + "): " + section.title());
                log.info("Analisi sezione {}/{}: '{}'", i + 1, totalSections, section.title());

                try {
                    String result = analyzeSection(section, model, sectionSystemPrompt, sectionUserTemplate);
                    sectionResults.add(result);
                } catch (Exception e) {
                    log.warn("Sezione '{}' non analizzata: {}", section.title(), e.getMessage());
                    sectionResults.add(buildFallbackSectionResult(section.title(), e.getMessage()));
                }
            }

            // Step 4 – REDUCE: synthesize and generate DOCX
            throwIfCancelled(jobId);
            updateProgress(jobId, 82, "Aggregazione risultati e generazione report Word");
            byte[] docxContent = generateDocxReport(sectionResults, originalFilename, model, synthesisSystemPrompt, synthesisUserTemplate);

            // Step 5 – Save result
            ContractAnalysis job = loadJob(jobId);
            String fileName = job.getResultFileName() != null
                    ? job.getResultFileName()
                    : "risk-analysis-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".docx";

            job.setStatus(ContractAnalysis.JobStatus.COMPLETED);
            job.setProgress(100);
            job.setCurrentStep("Analisi completata");
            job.setResultFileName(fileName);
            job.setResultFileContent(docxContent);
            contractAnalysisRepository.save(job);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("END analisi contratto | jobId={} | model={} | tempo totale={}s", jobId, model, elapsed / 1000);

        } catch (JobCancelledException e) {
            log.info("Job annullato dall'utente | jobId={}", jobId);
        } catch (Exception e) {
            log.error("ERRORE analisi contratto | jobId={}", jobId, e);
            ContractAnalysis job = loadJob(jobId);
            job.setStatus(ContractAnalysis.JobStatus.FAILED);
            job.setErrorMessage(e.getMessage());
            job.setCurrentStep(truncate("Errore: " + e.getMessage(), 500));
            contractAnalysisRepository.save(job);
        }
    }

    // -----------------------------------------------------------------------
    // Text extraction
    // -----------------------------------------------------------------------

    private String extractTextFromPdf(byte[] pdfBytes) {
        TikaDocumentReader reader = new TikaDocumentReader(new ByteArrayResource(pdfBytes));
        List<Document> docs = reader.get();
        return docs.stream()
                .map(Document::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n\n"));
    }

    // -----------------------------------------------------------------------
    // MAP: single section analysis
    // -----------------------------------------------------------------------

    private String analyzeSection(ContractSection section, String model, String sectionSystemPrompt, String sectionUserTemplate) throws InterruptedException {
        String userPrompt = buildSectionUserPrompt(section, sectionUserTemplate);
        int estimatedTokens = estimateTokens(sectionSystemPrompt + userPrompt) + 600;

        log.debug("Prompt sezione '{}' ({} chars):\n{}", section.title(), userPrompt.length(), userPrompt);

        // Rate limiter attivo solo per Anthropic (ha limiti TPM)
        if (ModelChatClientFactory.isAnthropicModel(model)) {
            rateLimiter.waitIfNeeded(estimatedTokens);
        }

        ChatResponse response = modelFactory.call(model, sectionSystemPrompt, userPrompt, 1500, true);

        int actualTokens = extractActualTokens(response, estimatedTokens);
        if (ModelChatClientFactory.isAnthropicModel(model)) {
            rateLimiter.recordUsage(actualTokens);
        }

        String result = response.getResult().getOutput().getText();
        log.debug("Risposta sezione '{}': {} caratteri | token: {}",
                section.title(), result != null ? result.length() : 0, actualTokens);
        return result;
    }

    private String buildSectionUserPrompt(ContractSection section, String template) {
        return template
                .replace("{SECTION_TITLE}", section.title())
                .replace("{SECTION_CONTENT}", section.content());
    }

    private String buildFallbackSectionResult(String title, String error) {
        return """
                {"sezione": "%s", "rischi": [], "clausole_mancanti": [], "sommario": "Analisi non disponibile: %s"}
                """.formatted(title, error.replace("\"", "'"));
    }

    // -----------------------------------------------------------------------
    // REDUCE: final DOCX report
    // -----------------------------------------------------------------------

    private byte[] generateDocxReport(List<String> sectionResults, String originalFilename, String model, String synthesisSystemPrompt, String synthesisUserTemplate) throws Exception {
        String aggregatedContext = buildSynthesisContext(sectionResults);
        log.info("Contesto sintesi: {} caratteri (~{} token stimati)", aggregatedContext.length(), aggregatedContext.length() / 3);
        String synthesisPrompt = buildSynthesisPrompt(aggregatedContext, originalFilename, synthesisUserTemplate);

        log.info("Prompt sintesi finale: {} caratteri (~{} token stimati) | modello={}",
                synthesisPrompt.length(), synthesisPrompt.length() / 3, model);
        log.debug("System prompt di sintesi:\n{}", synthesisSystemPrompt);
        log.debug("User prompt di sintesi completo:\n{}", synthesisPrompt);

        int estimatedTokens = estimateTokens(synthesisSystemPrompt + synthesisPrompt) + 4096;
        if (ModelChatClientFactory.isAnthropicModel(model)) {
            rateLimiter.waitIfNeeded(estimatedTokens);
        }

        ChatResponse response = modelFactory.call(model, synthesisSystemPrompt, synthesisPrompt, 32000, false);

        int actualTokens = extractActualTokens(response, estimatedTokens);
        if (ModelChatClientFactory.isAnthropicModel(model)) {
            rateLimiter.recordUsage(actualTokens);
        }

        String reportJson = response.getResult().getOutput().getText();
        log.info("JSON sintesi ricevuto: {} caratteri | token totali: {}",
                reportJson != null ? reportJson.length() : 0, actualTokens);
        log.debug("JSON sintesi completo:\n{}", reportJson);

        return reportBuilder.build(reportJson, originalFilename);
    }

    // ~130k token budget for context (200k limit - 16k output - 4k prompt overhead)
    private static final int MAX_SYNTHESIS_CHARS = 390_000;

    private String buildSynthesisContext(List<String> sectionResults) {
        StringBuilder sb = new StringBuilder();
        int totalSections = sectionResults.size();

        for (int i = 0; i < totalSections; i++) {
            String cleaned = compactJson(cleanJsonResult(sectionResults.get(i)));
            String entry = "=== SEZIONE " + (i + 1) + " ===\n" + cleaned + "\n\n";

            if (sb.length() + entry.length() > MAX_SYNTHESIS_CHARS) {
                sb.append("=== [").append(totalSections - i).append(" sezioni omesse per limite dimensione] ===\n");
                log.warn("Contesto sintesi troncato a {} sezioni su {} (limite {} chars)",
                        i, totalSections, MAX_SYNTHESIS_CHARS);
                break;
            }
            sb.append(entry);
        }

        return sb.toString();
    }

    private String compactJson(String json) {
        if (json == null) return "{}";
        return json.replaceAll("\\s{2,}", " ").strip();
    }

    private String buildSynthesisPrompt(String context, String originalFilename, String template) {
        return template
                .replace("{ORIGINAL_FILENAME}", originalFilename)
                .replace("{SECTIONS_ANALYSIS}", context);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String cleanJsonResult(String rawResult) {
        if (rawResult == null) return "{}";
        String cleaned = rawResult.strip();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("```$", "").strip();
        }
        // Tronca qualsiasi testo prima del { e dopo l'ultima } (es. postamble di Gemini)
        int start = cleaned.indexOf('{');
        if (start >= 0) {
            int end = cleaned.lastIndexOf('}');
            if (end > start) cleaned = cleaned.substring(start, end + 1);
        }
        return cleaned;
    }

    private int estimateTokens(String text) {
        return text.length() / 3;
    }

    private int extractActualTokens(ChatResponse response, int fallback) {
        try {
            if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                return (int) response.getMetadata().getUsage().getTotalTokens();
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private void updateJob(UUID jobId, ContractAnalysis.JobStatus status, int progress, String step) {
        ContractAnalysis job = loadJob(jobId);
        job.setStatus(status);
        job.setProgress(progress);
        job.setCurrentStep(truncate(step, 500));
        contractAnalysisRepository.save(job);
    }

    private void updateProgress(UUID jobId, int progress, String step) {
        ContractAnalysis job = loadJob(jobId);
        job.setProgress(progress);
        job.setCurrentStep(truncate(step, 500));
        contractAnalysisRepository.save(job);
    }

    private String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 3) + "...";
    }

    private void throwIfCancelled(UUID jobId) {
        if (loadJob(jobId).getStatus() == ContractAnalysis.JobStatus.CANCELLED) {
            throw new JobCancelledException(jobId);
        }
    }

    private ContractAnalysis loadJob(UUID jobId) {
        return contractAnalysisRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("Job non trovato: " + jobId));
    }
}
