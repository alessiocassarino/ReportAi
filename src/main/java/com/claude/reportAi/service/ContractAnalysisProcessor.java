package com.claude.reportAi.service;

import com.claude.reportAi.entities.ContractAnalysisJob;
import com.claude.reportAi.repository.ContractAnalysisJobRepository;
import com.claude.reportAi.service.ContractSectionExtractor.ContractSection;
import com.claude.reportAi.utils.AnthropicFileCleanupClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.AnthropicSkillsResponseHelper;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
import org.springframework.ai.chat.client.ChatClient;
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
import java.util.Map;
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

    private final ContractAnalysisJobRepository jobRepository;
    private final ContractSectionExtractor sectionExtractor;
    private final TokenRateLimiter rateLimiter;
    private final ChatClient chatClient;
    private final AnthropicApi anthropicApi;
    private final AnthropicFileCleanupClient fileCleanupClient;

    // -----------------------------------------------------------------------
    // Prompts
    // -----------------------------------------------------------------------

    private static final String SECTION_SYSTEM_PROMPT = """
            Sei un contract manager senior con oltre 30 anni di esperienza internazionale in Oil & Gas.
            Il tuo compito è analizzare sezioni di contratti commerciali e identificare rischi per il Contractor.

            Regole obbligatorie:
            - Ragiona SEMPRE nell'interesse del Contractor, non essere neutrale.
            - Rispondi ESCLUSIVAMENTE con un oggetto JSON valido, senza markdown, senza testo prima o dopo.
            - Se la sezione non contiene clausole rilevanti, restituisci un JSON con "rischi": [].
            - Sii diretto, concreto, professionale.
            """;

    private static final String SYNTHESIS_SYSTEM_PROMPT = """
            Sei un contract manager senior con oltre 30 anni di esperienza internazionale in Oil & Gas.
            Generi report professionali di analisi contrattuale in italiano, adatti a un board executive.
            Usa un linguaggio diretto, autorevole e non neutrale: stai difendendo gli interessi del Contractor.
            """;

    // -----------------------------------------------------------------------
    // Entry point (called by ContractAnalysisService)
    // -----------------------------------------------------------------------

    @Async("contractAnalysisExecutor")
    public void processAsync(UUID jobId, byte[] pdfBytes, String originalFilename) {
        log.info("START analisi contratto | jobId={} | file={}", jobId, originalFilename);
        long startTime = System.currentTimeMillis();

        try {
            // Step 1 - Extract text
            updateJob(jobId, ContractAnalysisJob.JobStatus.PROCESSING, 5, "Estrazione testo dal PDF");
            String fullText = extractTextFromPdf(pdfBytes);

            if (fullText.isBlank()) {
                throw new IllegalStateException("Impossibile estrarre testo dal PDF. Verificare che il file non sia scansionato.");
            }
            log.info("Testo estratto: {} caratteri", fullText.length());

            // Step 2 - Split into sections
            updateProgress(jobId, 10, "Identificazione sezioni del contratto");
            List<ContractSection> sections = sectionExtractor.extractSections(fullText);
            log.info("Sezioni identificate: {}", sections.size());

            // Step 3 - MAP: analyze each section
            List<String> sectionResults = new ArrayList<>();
            int totalSections = sections.size();

            for (int i = 0; i < totalSections; i++) {
                ContractSection section = sections.get(i);
                int progress = 10 + (int) ((i / (double) totalSections) * 70); // 10% → 80%

                updateProgress(jobId, progress, "Analisi sezione (" + (i + 1) + "/" + totalSections + "): " + section.title());
                log.info("Analisi sezione {}/{}: '{}'", i + 1, totalSections, section.title());

                try {
                    String result = analyzeSection(section);
                    sectionResults.add(result);
                } catch (Exception e) {
                    log.warn("Sezione '{}' non analizzata: {}", section.title(), e.getMessage());
                    sectionResults.add(buildFallbackSectionResult(section.title(), e.getMessage()));
                }
            }

            // Step 4 - REDUCE: synthesize and generate DOCX
            updateProgress(jobId, 82, "Aggregazione risultati e generazione report Word");
            byte[] docxContent = generateDocxReport(sectionResults, originalFilename);

            // Step 5 - Save result
            String fileName = "risk-analysis-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    + ".docx";

            ContractAnalysisJob job = loadJob(jobId);
            job.setStatus(ContractAnalysisJob.JobStatus.COMPLETED);
            job.setProgress(100);
            job.setCurrentStep("Analisi completata");
            job.setResultFileName(fileName);
            job.setResultFileContent(docxContent);
            jobRepository.save(job);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("END analisi contratto | jobId={} | tempo totale={}s", jobId, elapsed / 1000);

        } catch (Exception e) {
            log.error("ERRORE analisi contratto | jobId={}", jobId, e);
            ContractAnalysisJob job = loadJob(jobId);
            job.setStatus(ContractAnalysisJob.JobStatus.FAILED);
            job.setErrorMessage(e.getMessage());
            job.setCurrentStep("Errore: " + e.getMessage());
            jobRepository.save(job);
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

    private String analyzeSection(ContractSection section) throws InterruptedException {
        String userPrompt = buildSectionUserPrompt(section);
        int estimatedTokens = estimateTokens(SECTION_SYSTEM_PROMPT + userPrompt) + 600;

        rateLimiter.waitIfNeeded(estimatedTokens);

        ChatResponse response = chatClient.prompt()
                .system(SECTION_SYSTEM_PROMPT)
                .user(userPrompt)
                .options(AnthropicChatOptions.builder()
                        .model("claude-sonnet-4-5")
                        .maxTokens(1500)
                        .temperature(0.1d)
                        .cacheOptions(AnthropicCacheOptions.builder()
                                .strategy(AnthropicCacheStrategy.SYSTEM_ONLY)
                                .build())
                        .build())
                .call()
                .chatResponse();

        int actualTokens = extractActualTokens(response, estimatedTokens);
        rateLimiter.recordUsage(actualTokens);

        return response.getResult().getOutput().getText();
    }

    private String buildSectionUserPrompt(ContractSection section) {
        return """
                Analizza la seguente sezione del contratto dal punto di vista del Contractor.

                Sezione: %s

                ---
                %s
                ---

                Rispondi con questo JSON (nessun testo aggiuntivo):
                {
                  "sezione": "<titolo della sezione>",
                  "rischi": [
                    {
                      "clausola": "<riferimento clausola, es. 5.3>",
                      "descrizione": "<descrizione del rischio per il Contractor>",
                      "livello": "ALTO|MEDIO|BASSO",
                      "raccomandazione": "<proposta di modifica o tutela>"
                    }
                  ],
                  "clausole_mancanti": ["<clausola assente ma necessaria>"],
                  "sommario": "<sintesi in 1-2 frasi>"
                }
                """.formatted(section.title(), section.content());
    }

    private String buildFallbackSectionResult(String title, String error) {
        return """
                {"sezione": "%s", "rischi": [], "clausole_mancanti": [], "sommario": "Analisi non disponibile: %s"}
                """.formatted(title, error.replace("\"", "'"));
    }

    // -----------------------------------------------------------------------
    // REDUCE: final DOCX report
    // -----------------------------------------------------------------------

    private byte[] generateDocxReport(List<String> sectionResults, String originalFilename) throws InterruptedException {
        String aggregatedContext = buildSynthesisContext(sectionResults);
        log.info("Contesto sintesi: {} caratteri (~{} token stimati)", aggregatedContext.length(), aggregatedContext.length() / 3);
        String synthesisPrompt = buildSynthesisPrompt(aggregatedContext, originalFilename);

        int estimatedTokens = estimateTokens(SYNTHESIS_SYSTEM_PROMPT + synthesisPrompt) + 4096;
        rateLimiter.waitIfNeeded(estimatedTokens);

        ChatResponse response = chatClient.prompt()
                .system(SYNTHESIS_SYSTEM_PROMPT)
                .user(synthesisPrompt)
                .options(AnthropicChatOptions.builder()
                        .model("claude-sonnet-4-5")
                        .maxTokens(32000)
                        .httpHeaders(Map.of("anthropic-beta", "output-128k-2025-02-19"))
                        .skill(AnthropicApi.AnthropicSkill.DOCX)
                        .build())
                .call()
                .chatResponse();

        int actualTokens = extractActualTokens(response, estimatedTokens);
        rateLimiter.recordUsage(actualTokens);

        List<String> fileIds = AnthropicSkillsResponseHelper.extractFileIds(response);
        if (fileIds == null || fileIds.isEmpty()) {
            String rawText = response.getResult().getOutput().getText();
            log.error("Claude non ha generato DOCX. Testo: {}",
                    rawText != null && rawText.length() > 500 ? rawText.substring(0, 500) : rawText);
            Object anthropicRaw = response.getMetadata().get("anthropic-response");
            if (anthropicRaw instanceof AnthropicApi.ChatCompletionResponse cr) {
                log.error("Content blocks grezzi: {}", cr.content());
                log.error("Stop reason: {}", cr.stopReason());
            }
            throw new IllegalStateException("Claude non ha generato nessun file DOCX nella risposta finale.");
        }

        String fileId = fileIds.get(0);
        byte[] content = anthropicApi.downloadFile(fileId);
        log.info("File DOCX scaricato: {} bytes", content.length);

        try {
            fileCleanupClient.deleteFileFromAnthropic(fileId);
            log.info("File eliminato da Anthropic: {}", fileId);
        } catch (Exception e) {
            log.warn("Impossibile eliminare il file da Anthropic (file_id={}): {}", fileId, e.getMessage());
        }

        return content;
    }

    private String buildSynthesisContext(List<String> sectionResults) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sectionResults.size(); i++) {
            sb.append("=== RISULTATO SEZIONE ").append(i + 1).append(" ===\n");
            sb.append(cleanJsonResult(sectionResults.get(i)));
            sb.append("\n\n");
        }
        return sb.toString();
    }

    private String buildSynthesisPrompt(String context, String originalFilename) {
        return """
                Hai analizzato sezione per sezione il contratto "%s".
                Di seguito trovi i risultati JSON di ogni sezione analizzata.

                [RISULTATI ANALISI PER SEZIONE]
                %s

                Genera un report professionale Word con:

                1. **Executive Summary**
                   - I 3-5 rischi più critici in assoluto
                   - Valutazione complessiva del contratto (favorevole / equilibrato / sfavorevole al Contractor)
                   - Raccomandazione finale (firmare / negoziare / rifiutare)

                2. **Matrice dei Rischi** (tabella)
                   Colonne: # | Sezione | Clausola | Rischio | Livello | Azione richiesta
                   Ordina per livello decrescente (ALTO → MEDIO → BASSO)

                3. **Analisi Dettagliata per Sezione**
                   Per ogni sezione: titolo, rischi trovati con descrizione e raccomandazione specifica

                4. **Top 5 Clausole da Negoziare**
                   Con formulazione alternativa proposta

                5. **Clausole Mancanti**
                   Clausole assenti che dovrebbero essere inserite a tutela del Contractor

                Lingua: italiano. Stile: diretto, professionale, adatto a un board executive.
                """.formatted(originalFilename, context);
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

    private void updateJob(UUID jobId, ContractAnalysisJob.JobStatus status, int progress, String step) {
        ContractAnalysisJob job = loadJob(jobId);
        job.setStatus(status);
        job.setProgress(progress);
        job.setCurrentStep(step);
        jobRepository.save(job);
    }

    private void updateProgress(UUID jobId, int progress, String step) {
        ContractAnalysisJob job = loadJob(jobId);
        job.setProgress(progress);
        job.setCurrentStep(step);
        jobRepository.save(job);
    }

    private ContractAnalysisJob loadJob(UUID jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("Job non trovato: " + jobId));
    }
}
