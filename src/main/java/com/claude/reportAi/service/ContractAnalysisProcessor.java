package com.claude.reportAi.service;

import com.claude.reportAi.entities.ContractAnalysis;
import com.claude.reportAi.exception.JobCancelledException;
import com.claude.reportAi.repository.ContractAnalysisRepository;
import com.claude.reportAi.service.ContractSectionExtractor.ContractSection;
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
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
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
            Sintetizzi analisi contrattuali in un unico JSON strutturato, in italiano, per la generazione di report executive.
            Rispondi ESCLUSIVAMENTE con un oggetto JSON valido, senza markdown, senza testo prima o dopo.
            Usa un linguaggio diretto, autorevole e non neutrale: stai difendendo gli interessi del Contractor.
            """;

    // -----------------------------------------------------------------------
    // Entry point (called by ContractAnalysisService)
    // -----------------------------------------------------------------------

    @Async("contractAnalysisExecutor")
    public void processAsync(UUID jobId, byte[] pdfBytes, String originalFilename, String model) {
        log.info("START analisi contratto | jobId={} | file={} | model={}", jobId, originalFilename, model);
        long startTime = System.currentTimeMillis();

        try {
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

            // Step 3 – MAP: analyze sections in parallel (MAP_PARALLELISM concurrent calls)
            int totalSections = sections.size();
            String[] resultsArr = new String[totalSections];
            AtomicInteger completedCount = new AtomicInteger(0);
            ExecutorService mapPool = Executors.newFixedThreadPool(Math.min(MAP_PARALLELISM, totalSections));
            List<CompletableFuture<Void>> futures = new ArrayList<>(totalSections);

            for (int i = 0; i < totalSections; i++) {
                throwIfCancelled(jobId);
                final int idx = i;
                final ContractSection section = sections.get(i);
                futures.add(CompletableFuture.runAsync(() -> {
                    String result;
                    try {
                        result = analyzeSection(section, model);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        result = buildFallbackSectionResult(section.title(), "Analisi interrotta");
                    } catch (Exception e) {
                        log.warn("Sezione '{}' non analizzata: {}", section.title(), e.getMessage());
                        result = buildFallbackSectionResult(section.title(), e.getMessage());
                    }
                    resultsArr[idx] = result;
                    int done = completedCount.incrementAndGet();
                    int progress = 10 + (int) ((done / (double) totalSections) * 70);
                    log.info("Analisi sezione {}/{}: '{}'", done, totalSections, section.title());
                    try {
                        updateProgress(jobId, progress,
                                "Analisi sezione " + done + "/" + totalSections + ": " + section.title());
                    } catch (Exception ignored) {}
                }, mapPool));
            }

            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Analisi parallela interrotta per jobId={}", jobId);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) throw re;
                throw new RuntimeException("Errore nell'analisi sezioni", cause);
            } finally {
                mapPool.shutdownNow();
            }

            List<String> sectionResults = Arrays.asList(resultsArr);

            // Step 4 – REDUCE: synthesize and generate DOCX
            throwIfCancelled(jobId);
            updateProgress(jobId, 82, "Aggregazione risultati e generazione report Word");
            byte[] docxContent = generateDocxReport(sectionResults, originalFilename, model);

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

    private String analyzeSection(ContractSection section, String model) throws InterruptedException {
        String userPrompt = buildSectionUserPrompt(section);
        int estimatedTokens = estimateTokens(SECTION_SYSTEM_PROMPT + userPrompt) + 600;

        log.debug("Prompt sezione '{}' ({} chars):\n{}", section.title(), userPrompt.length(), userPrompt);

        // Rate limiter attivo solo per Anthropic (ha limiti TPM)
        if (ModelChatClientFactory.isAnthropicModel(model)) {
            rateLimiter.waitIfNeeded(estimatedTokens);
        }

        ChatResponse response = modelFactory.call(model, SECTION_SYSTEM_PROMPT, userPrompt, 1500, true);

        int actualTokens = extractActualTokens(response, estimatedTokens);
        if (ModelChatClientFactory.isAnthropicModel(model)) {
            rateLimiter.recordUsage(actualTokens);
        }

        String result = response.getResult().getOutput().getText();
        log.debug("Risposta sezione '{}': {} caratteri | token: {}",
                section.title(), result != null ? result.length() : 0, actualTokens);
        return result;
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

    private byte[] generateDocxReport(List<String> sectionResults, String originalFilename, String model) throws Exception {
        String aggregatedContext = buildSynthesisContext(sectionResults);
        log.info("Contesto sintesi: {} caratteri (~{} token stimati)", aggregatedContext.length(), aggregatedContext.length() / 3);
        String synthesisPrompt = buildSynthesisPrompt(aggregatedContext, originalFilename);

        log.info("Prompt sintesi finale: {} caratteri (~{} token stimati) | modello={}",
                synthesisPrompt.length(), synthesisPrompt.length() / 3, model);
        log.debug("System prompt di sintesi:\n{}", SYNTHESIS_SYSTEM_PROMPT);
        log.debug("User prompt di sintesi completo:\n{}", synthesisPrompt);

        // maxTokens=16000: covers the synthesis JSON for large contracts (14+ sections).
        // waitIfNeeded è intenzionalmente omesso: la sintesi è una singola chiamata, non un loop in burst.
        // Applicare il rate-limiter qui causa starvation quando job concorrenti hanno già saturato
        // la finestra TPM con le chiamate MAP. recordUsage viene comunque chiamato dopo.
        int estimatedTokens = estimateTokens(SYNTHESIS_SYSTEM_PROMPT + synthesisPrompt) + 16000;

        ChatResponse response = modelFactory.call(model, SYNTHESIS_SYSTEM_PROMPT, synthesisPrompt, 16000, false);

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

    // Max concurrent section analysis calls in the MAP phase.
    // 4 parallel calls cut total MAP time to ~1/4 vs sequential.
    private static final int MAP_PARALLELISM = 4;

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

    private String buildSynthesisPrompt(String context, String originalFilename) {
        return """
                Hai analizzato sezione per sezione il contratto "%s".
                Di seguito trovi i risultati JSON di ogni sezione analizzata.

                [RISULTATI ANALISI PER SEZIONE]
                %s

                Restituisci un unico oggetto JSON con questa struttura esatta (nessun testo prima o dopo):
                {
                  "valutazione_complessiva": "SFAVOREVOLE|EQUILIBRATO|FAVOREVOLE",
                  "raccomandazione_finale": "FIRMARE|NEGOZIARE|RIFIUTARE",
                  "executive_summary": "<sintesi in 3-5 frasi per il board>",
                  "rischi_critici": [
                    {"sezione": "", "clausola": "", "descrizione": "", "livello": "ALTO|MEDIO|BASSO"}
                  ],
                  "matrice_rischi": [
                    {"sezione": "", "clausola": "", "rischio": "", "livello": "ALTO|MEDIO|BASSO", "azione": ""}
                  ],
                  "analisi_sezioni": [
                    {
                      "titolo": "",
                      "sommario": "",
                      "rischi": [
                        {"clausola": "", "descrizione": "", "livello": "ALTO|MEDIO|BASSO", "raccomandazione": ""}
                      ],
                      "clausole_mancanti": [""]
                    }
                  ],
                  "top5_clausole": [
                    {"riferimento": "", "testo_attuale": "", "testo_proposto": ""}
                  ],
                  "clausole_mancanti_globali": [""]
                }

                Regole:
                - matrice_rischi ordinata per livello decrescente (ALTO → MEDIO → BASSO)
                - rischi_critici: massimo 5 rischi, solo i più gravi
                - top5_clausole: le 5 clausole più critiche da rinegoziare con testo alternativo proposto
                - Lingua: italiano
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
