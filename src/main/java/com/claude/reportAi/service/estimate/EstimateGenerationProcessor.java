package com.claude.reportAi.service.estimate;

import com.claude.reportAi.entities.Estimate;
import com.claude.reportAi.repository.EstimateRepository;
import com.claude.reportAi.service.ModelChatClientFactory;
import com.claude.reportAi.service.TokenRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class EstimateGenerationProcessor {

    private final EstimateRepository estimateRepository;
    private final ProjectInfoExtractor projectInfoExtractor;
    private final InternalPricingRetriever internalPricingRetriever;
    private final WebSearchService webSearchService;
    private final ModelChatClientFactory modelFactory;
    private final TokenRateLimiter tokenRateLimiter;
    private final EstimateReportBuilder estimateReportBuilder;
    private final PdfPageImageExtractor pdfPageImageExtractor;
    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT = """
            Sei un cost estimator / tendering manager senior con 30 anni di esperienza specifica in EPC oil & gas onshore.
            Conosci perfettamente i processi di ingegneria e i loro costi, i materiali, le operazioni di cantiere, le
            interfacce tra civile e meccanico e elettrico e strumentale, i rischi reali di progetto, le normative locali e le pratiche di mercato.

            Regole obbligatorie:
            - Ragiona SEMPRE nell'interesse del Contractor
            - Sulle stime di Costruzione, priorità assoluta ai prezzi interni aziendali forniti; usa i dati di mercato per le voci mancanti
            - Fornisci stime di durata tenendo in considerazione la schedula (Gantt) o le tempistiche fornite nel documento
            - Fornisci stime realistiche e conservative (meglio sovrastimare)
            - Tutti gli importi in USD
            - Lingua del report: italiano
            - Rispondi ESCLUSIVAMENTE con un oggetto JSON valido, senza markdown, senza testo prima o dopo
            """;

    @Async("reportGenerationExecutor")
    public void processAsync(UUID jobId, byte[] pdfBytes, String originalFilename, String model) {
        log.info("START generazione preventivo | jobId={} | file={} | model={}", jobId, originalFilename, model);
        long startTime = System.currentTimeMillis();

        try {
            // Step 1 – Estrazione testo
            updateJob(jobId, Estimate.JobStatus.PROCESSING, 2, "Estrazione testo dal documento");
            String fullText = extractText(pdfBytes);

            if (fullText == null || fullText.isBlank()) {
                failJob(jobId, "Impossibile estrarre testo dal documento");
                return;
            }
            log.info("Testo estratto: {} caratteri", fullText.length());

            // Step 2 – Analisi struttura progetto
            updateProgress(jobId, 10, "Analisi struttura progetto");
            ProjectInfoExtractor.ProjectInfo info = projectInfoExtractor.extract(fullText, model);
            log.info("Info estratte: nazione={}, tipo={}, km={}, mesi={}",
                    info.nazione(), info.tipoProgetto(), info.lunghezzaKm(), info.durataMesi());

            // Step 3 – Recupero prezzi interni
            updateProgress(jobId, 25, "Recupero prezzi interni aziendali");
            String internalPricing = internalPricingRetriever.retrieveContext(info);

            // Step 4 – Ricerche web (7 categorie, progress 35→65)
            String paese = info.nazione() != null ? info.nazione() : "N/D";
            String tipo  = info.tipoProgetto() != null ? info.tipoProgetto().toLowerCase() : "pipeline";

            List<Map.Entry<String, String>> searchCategories = List.of(
                    Map.entry("COSTI MATERIALI DI PROGETTO",
                            tipo + " pipeline pipe valves fittings material cost " + paese + " 2024 2025 USD"),
                    Map.entry("COSTI DI MOBILIZZAZIONE DALL'ITALIA",
                            "heavy equipment mobilization Italy " + paese + " transport logistics cost 2024"),
                    Map.entry("BASI LOGISTICHE E ACCOMMODATION",
                            "labor camp accommodation catering oil gas " + paese + " daily rate USD person 2024"),
                    Map.entry("COSTI SICUREZZA",
                            "security services requirements oil gas construction " + paese + " cost 2024"),
                    Map.entry("MATERIALI CONSUMABILI",
                            "welding electrodes fuel diesel lubricants PPE cement steel " + paese + " construction prices 2024"),
                    Map.entry("TASSAZIONE E ONERI FISCALI",
                            "WHT withholding tax VAT customs duty foreign EPC contractor " + paese + " oil gas 2025"),
                    Map.entry("COSTO DELLA MANODOPERA",
                            paese + " pipeline construction worker salary daily rate USD 2024 local expat")
            );

            Map<String, String> searchResults = new LinkedHashMap<>();
            int searchTotal = searchCategories.size();
            for (int i = 0; i < searchTotal; i++) {
                Map.Entry<String, String> entry = searchCategories.get(i);
                String categoria = entry.getKey();
                String query     = entry.getValue();

                int progress = 35 + (int) ((i / (double) searchTotal) * 30); // 35→65
                updateProgress(jobId, progress, "Ricerca: " + categoria);

                List<WebSearchService.SearchResult> results = webSearchService.search(query);
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < results.size(); j++) {
                    WebSearchService.SearchResult r = results.get(j);
                    sb.append("[").append(j + 1).append("] ").append(r.title()).append("\n");
                    sb.append(r.url()).append("\n");
                    sb.append(r.content()).append("\n\n");
                }
                searchResults.put(categoria, sb.toString().trim());
            }

            // Step 5 – Estrazione immagini (Gantt, grafici) e generazione preventivo con LLM
            List<byte[]> pageImages = List.of();
            if (ModelChatClientFactory.isAnthropicModel(model)) {
                updateProgress(jobId, 67, "Estrazione immagini dal documento");
                pageImages = pdfPageImageExtractor.extractPageImages(pdfBytes);
                if (!pageImages.isEmpty()) {
                    log.info("Invio {} immagini PDF al modello per lettura Gantt/grafici", pageImages.size());
                }
            }

            updateProgress(jobId, 70, "Generazione preventivo con " + model);
            String userPrompt = buildUserPrompt(info, internalPricing, searchResults, !pageImages.isEmpty());

            int estimatedTokens = userPrompt.length() / 3;
            if (ModelChatClientFactory.isAnthropicModel(model)) {
                tokenRateLimiter.waitIfNeeded(estimatedTokens);
            }

            ChatResponse response = modelFactory.callWithImages(model, SYSTEM_PROMPT, userPrompt, 16000, false, pageImages);

            int actualTokens = extractActualTokens(response, estimatedTokens);
            if (ModelChatClientFactory.isAnthropicModel(model)) {
                tokenRateLimiter.recordUsage(actualTokens);
            }

            String reportJson = response.getResult().getOutput().getText();
            log.info("JSON preventivo ricevuto: {} caratteri", reportJson != null ? reportJson.length() : 0);

            // Step 6 – Generazione documento Word
            updateProgress(jobId, 85, "Generazione documento Word");
            byte[] docx = estimateReportBuilder.build(reportJson, info, originalFilename);

            // Step 7 – Salvataggio risultato
            String fileName = "estimate-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    + ".docx";

            Estimate job = loadJob(jobId);
            job.setStatus(Estimate.JobStatus.COMPLETED);
            job.setProgress(100);
            job.setCurrentStep("Preventivo completato");
            job.setResultFileName(fileName);
            job.setResultFileContent(docx);
            estimateRepository.save(job);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("END generazione preventivo | jobId={} | model={} | tempo={}s", jobId, model, elapsed / 1000);

        } catch (Exception e) {
            log.error("ERRORE generazione preventivo | jobId={}", jobId, e);
            try {
                Estimate job = loadJob(jobId);
                job.setStatus(Estimate.JobStatus.FAILED);
                job.setErrorMessage(truncate(e.getMessage(), 1000));
                job.setCurrentStep(truncate("Errore: " + e.getMessage(), 500));
                estimateRepository.save(job);
            } catch (Exception saveEx) {
                log.error("Impossibile salvare stato FAILED per jobId={}: {}", jobId, saveEx.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Text extraction
    // -------------------------------------------------------------------------

    private String extractText(byte[] pdfBytes) {
        TikaDocumentReader reader = new TikaDocumentReader(new ByteArrayResource(pdfBytes));
        List<Document> docs = reader.get();
        return docs.stream()
                .map(Document::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n\n"));
    }

    // -------------------------------------------------------------------------
    // User prompt builder
    // -------------------------------------------------------------------------

    private String buildUserPrompt(
            ProjectInfoExtractor.ProjectInfo info,
            String internalPricing,
            Map<String, String> searchResults,
            boolean hasImages) throws Exception {

        // Map.of() supporta max 10 entries — usiamo LinkedHashMap per mantenere l'ordine
        Map<String, Object> projectInfoMap = new LinkedHashMap<>();
        projectInfoMap.put("nazione",                info.nazione() != null ? info.nazione() : "N/D");
        projectInfoMap.put("tipo_progetto",          info.tipoProgetto() != null ? info.tipoProgetto() : "N/D");
        projectInfoMap.put("diametro_pollici",       info.diametroPollici() != null ? info.diametroPollici() : "N/D");
        projectInfoMap.put("lunghezza_km",           info.lunghezzaKm() != null ? info.lunghezzaKm() : "N/D");
        projectInfoMap.put("durata_mesi",            info.durataMesi() != null ? info.durataMesi() : "N/D");
        projectInfoMap.put("num_spread",             info.numSpread() != null ? info.numSpread() : "N/D");
        projectInfoMap.put("avanzamento_m_giorno",   info.avanzamentoMGiorno() != null ? info.avanzamentoMGiorno() : "N/D");
        projectInfoMap.put("scope_lavori",           info.scopeLavori() != null ? info.scopeLavori() : "N/D");
        projectInfoMap.put("zona_geografica",        info.zonaGeografica() != null ? info.zonaGeografica() : "N/D");
        projectInfoMap.put("pressione_progetto_bara",info.pressioneProgettoBara() != null ? info.pressioneProgettoBara() : "N/D");
        projectInfoMap.put("note_tecniche",          info.noteTecniche() != null ? info.noteTecniche() : "N/D");
        String projectInfoJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(projectInfoMap);

        String pricingSection = (internalPricing == null || internalPricing.isBlank())
                ? "Nessun dato interno disponibile. Usa esclusivamente dati di mercato."
                : internalPricing;

        StringBuilder sb = new StringBuilder();
        sb.append("[INFORMAZIONI PROGETTO ESTRATTE DAL DOCUMENTO]\n");
        sb.append(projectInfoJson).append("\n\n");

        sb.append("[PREZZI INTERNI AZIENDALI - DA UTILIZZARE CON PRIORITÀ MASSIMA]\n");
        sb.append(pricingSection).append("\n\n");

        sb.append("[DATI DI MERCATO AGGIORNATI - NAZIONE: ")
          .append(info.nazione() != null ? info.nazione() : "N/D").append("]\n");

        for (Map.Entry<String, String> entry : searchResults.entrySet()) {
            sb.append("=== ").append(entry.getKey()).append(" ===\n");
            String val = entry.getValue();
            sb.append(val.isBlank() ? "Nessun dato disponibile." : val).append("\n\n");
        }

        sb.append("[ISTRUZIONI]\n");
        if (hasImages) {
            sb.append("Sono allegate le immagini delle pagine principali del documento PDF.\n");
            sb.append("Analizza attentamente eventuali Gantt, cronoprogrammi, schemi tecnici o tabelle nelle immagini.\n");
            sb.append("Usa le informazioni visive per ricavare durate delle fasi, sequenze di attività e dati tecnici non presenti nel testo.\n");
        }
        sb.append("Genera un preventivo dettagliato per questo progetto.\n");

        String tipoUp = info.tipoProgetto() != null ? info.tipoProgetto().toUpperCase() : "";
        if ("PIPELINE".equals(tipoUp) && info.avanzamentoMGiorno() == null) {
            sb.append("Per la pipeline, considera un avanzamento medio di 600-700 m/giorno con un numero adeguato di mezzi per linea principale e tie-in in parallelo.\n");
        }

        sb.append("I costi finanziari, overhead e assicurazioni devono essere pari al 12% del totale parziale.\n");
        sb.append("Includi sempre: mobilizzazione, costruzione, subcontratti/forniture, indiretti, vitto/alloggio, contingency/finanziari.\n");
        sb.append("Tutti gli importi in USD. Il report deve essere dettagliato e professionale.\n\n");

        sb.append("Restituisci ESCLUSIVAMENTE questo JSON:\n");
        sb.append("""
                {
                  "nazione": "...",
                  "tipo_progetto": "...",
                  "executive_summary": "3-5 frasi per top management",
                  "quadro_economico": [
                    {"voce": "I - Mobilizzazione e Temporary Facilities", "importo_usd": 0, "percentuale": 0.0, "note": "..."},
                    {"voce": "II - Costruzione (mezzi + personale + carburante)", "importo_usd": 0, "percentuale": 0.0, "note": "..."},
                    {"voce": "III - Subcontratti e Forniture", "importo_usd": 0, "percentuale": 0.0, "note": "..."},
                    {"voce": "IV - Indiretti", "importo_usd": 0, "percentuale": 0.0, "note": "..."},
                    {"voce": "V - Vitto e Alloggio", "importo_usd": 0, "percentuale": 0.0, "note": "..."},
                    {"voce": "SUBTOTALE I-V", "importo_usd": 0, "percentuale": 100.0, "note": ""},
                    {"voce": "VI - Contingency e Costi Finanziari (12%)", "importo_usd": 0, "percentuale": 12.0, "note": ""},
                    {"voce": "VII - COSTO TOTALE", "importo_usd": 0, "percentuale": 0.0, "note": ""},
                    {"voce": "VIII - PREZZO (margine 8%)", "importo_usd": 0, "percentuale": 0.0, "note": ""}
                  ],
                  "kpi": {
                    "prezzo_totale_usd": 0,
                    "prezzo_al_km": null,
                    "prezzo_al_metro": null,
                    "prezzo_inch_metro": null,
                    "personale_diretto": 0,
                    "personale_indiretto": 0,
                    "personale_totale": 0,
                    "durata_mesi": 0,
                    "ore_uomo_stimate": 0
                  },
                  "analisi_dettaglio": [
                    {
                      "categoria": "Mobilizzazione e Temporary Facilities",
                      "importo_usd": 0,
                      "descrizione": "...",
                      "voci_principali": [
                        {"descrizione": "...", "quantita": "...", "costo_unitario_usd": "...", "totale_usd": 0}
                      ],
                      "assunzioni": ["..."],
                      "rischi": ["..."]
                    }
                  ],
                  "imposte_e_oneri": {
                    "wht_percentuale": "...",
                    "vat_percentuale": "...",
                    "customs": "...",
                    "impatto_stimato_usd": 0,
                    "note": "..."
                  },
                  "rischi_principali": [
                    {"categoria": "...", "descrizione": "...", "impatto": "ALTO|MEDIO|BASSO", "mitigazione": "..."}
                  ],
                  "cronoprogramma_sintetico": [
                    {"fase": "...", "durata": "...", "settimane": "...", "note": "..."}
                  ],
                  "note_finali": "..."
                }
                """);

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void updateJob(UUID jobId, Estimate.JobStatus status, int progress, String step) {
        Estimate job = loadJob(jobId);
        job.setStatus(status);
        job.setProgress(progress);
        job.setCurrentStep(truncate(step, 500));
        estimateRepository.save(job);
    }

    private void updateProgress(UUID jobId, int progress, String step) {
        Estimate job = loadJob(jobId);
        job.setProgress(progress);
        job.setCurrentStep(truncate(step, 500));
        estimateRepository.save(job);
    }

    private void failJob(UUID jobId, String message) {
        Estimate job = loadJob(jobId);
        job.setStatus(Estimate.JobStatus.FAILED);
        job.setErrorMessage(message);
        job.setCurrentStep(truncate("Errore: " + message, 500));
        estimateRepository.save(job);
    }

    private Estimate loadJob(UUID jobId) {
        return estimateRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("Job non trovato: " + jobId));
    }

    private String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 3) + "...";
    }

    private int extractActualTokens(ChatResponse response, int fallback) {
        try {
            if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                return response.getMetadata().getUsage().getTotalTokens();
            }
        } catch (Exception ignored) {}
        return fallback;
    }
}
