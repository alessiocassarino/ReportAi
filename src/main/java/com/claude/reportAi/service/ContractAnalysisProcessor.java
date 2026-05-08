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

    // -----------------------------------------------------------------------
    // Prompts
    // -----------------------------------------------------------------------

    private static final String SECTION_SYSTEM_PROMPT = """
            <role>
            You are a senior contract manager with over 30 years of international experience in Oil & Gas.
            Your task is to analyze sections of commercial contracts and identify risks for the Contractor.
            </role>

            <guidelines>
            - ALWAYS reason in the Contractor's interest — do not be neutral.
            - Respond ONLY with a valid JSON object, no markdown, no text before or after.
            - If the section contains no relevant clauses, return a JSON with "rischi": [].
            - Be direct, concrete, and professional.
            - All output must be in Italian.
            </guidelines>
            """;

    private static final String SYNTHESIS_SYSTEM_PROMPT = """
            <role>
            You are a senior contract manager with over 30 years of international experience in Oil & Gas.
            You synthesize contractual analyses into a single structured JSON for executive report generation.
            </role>

            <guidelines>
            - Respond ONLY with a valid JSON object, no markdown, no text before or after.
            - Use direct, authoritative, non-neutral language: you are defending the Contractor's interests.
            - All output must be in Italian.
            - Prioritize the most critical risks and actionable recommendations.
            - executive_summary must be written for C-level audience, 3-5 sentences maximum.
            </guidelines>
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
                    String result = analyzeSection(section, model);
                    sectionResults.add(result);
                } catch (Exception e) {
                    log.warn("Sezione '{}' non analizzata: {}", section.title(), e.getMessage());
                    sectionResults.add(buildFallbackSectionResult(section.title(), e.getMessage()));
                }
            }

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
                <task>
                Analyze the following contract section from the Contractor's perspective and identify all risks.
                </task>

                <steps>
                1. Read the section and identify every clause with potential risk for the Contractor.
                2. For each risk, assess severity (ALTO/MEDIO/BASSO) based on financial and legal impact.
                3. Identify missing protective clauses that should be present but are absent.
                4. Write a concise 1-2 sentence summary of the section's overall risk profile.
                5. Return the result as a valid JSON object in Italian.
                </steps>

                <input>
                <section_title>%s</section_title>
                <section_content>
                %s
                </section_content>
                </input>

                <output_format>
                {
                  "sezione": "<titolo della sezione>",
                  "rischi": [
                    {
                      "clausola": "<riferimento clausola, es. 5.3>",
                      "descrizione": "<descrizione del rischio per il Contractor>",
                      "livello": "ALTO|MEDIO|BASSO",
                      "raccomandazione": "<proposta di modifica o tutela specifica>"
                    }
                  ],
                  "clausole_mancanti": ["<clausola assente ma necessaria>"],
                  "sommario": "<sintesi in 1-2 frasi>"
                }
                </output_format>

                <guidelines>
                - Return ONLY the JSON object, no additional text.
                - All output in Italian.
                - livello ALTO: high financial or legal exposure for the Contractor.
                - livello MEDIO: moderate impact, manageable with negotiation.
                - livello BASSO: minor risk, low financial impact.
                - raccomandazione must be specific and actionable, not generic advice.
                </guidelines>

                <example>
                Input: "5 - Penali e Liquidated Damages — La penale per ritardo è pari all'1%% del valore contrattuale per ogni settimana di ritardo, senza limite massimo."
                Output:
                {
                  "sezione": "5 - Penali e Liquidated Damages",
                  "rischi": [
                    {
                      "clausola": "5.1",
                      "descrizione": "Penale settimanale dell'1%% senza cap: esposizione illimitata per il Contractor in caso di ritardi prolungati.",
                      "livello": "ALTO",
                      "raccomandazione": "Introdurre cap massimo al 10%% del valore contrattuale e clausola di proroga automatica per forza maggiore documentata."
                    }
                  ],
                  "clausole_mancanti": ["Cap sulle penali totali", "Definizione di forza maggiore"],
                  "sommario": "Sezione ad alto rischio: l'assenza di un cap sulle penali espone il Contractor a passività potenzialmente superiori al margine di progetto."
                }
                </example>
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

        int estimatedTokens = estimateTokens(SYNTHESIS_SYSTEM_PROMPT + synthesisPrompt) + 4096;
        if (ModelChatClientFactory.isAnthropicModel(model)) {
            rateLimiter.waitIfNeeded(estimatedTokens);
        }

        ChatResponse response = modelFactory.call(model, SYNTHESIS_SYSTEM_PROMPT, synthesisPrompt, 32000, false);

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

    private String buildSynthesisPrompt(String context, String originalFilename) {
        return """
                <task>
                You have analyzed the contract "%s" section by section.
                Synthesize all section results into a single comprehensive risk assessment JSON for executive reporting.
                </task>

                <steps>
                1. Review all section analysis results provided in the input below.
                2. Identify the top 5 most critical risks across all sections.
                3. Build the risk matrix ordered by severity (ALTO → MEDIO → BASSO).
                4. Determine the overall contract assessment: SFAVOREVOLE, EQUILIBRATO, or FAVOREVOLE.
                5. Formulate the final recommendation: FIRMARE, NEGOZIARE, or RIFIUTARE.
                6. Draft a 3-5 sentence executive summary for board-level presentation in Italian.
                7. Select the top 5 most critical clauses to renegotiate and propose alternative text.
                8. Return the complete structured JSON.
                </steps>

                <input>
                <section_analysis_results>
                %s
                </section_analysis_results>
                </input>

                <output_format>
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
                </output_format>

                <guidelines>
                - Return ONLY the JSON object, no additional text.
                - All output in Italian.
                - matrice_rischi: ordered by severity descending (ALTO → MEDIO → BASSO).
                - rischi_critici: maximum 5 risks, only the most severe ones.
                - top5_clausole: the 5 most critical clauses to renegotiate with specific proposed alternative text.
                - executive_summary: direct, non-neutral tone defending the Contractor's interests.
                </guidelines>
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
