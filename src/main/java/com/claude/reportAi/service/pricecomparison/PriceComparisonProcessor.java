package com.claude.reportAi.service.pricecomparison;

import com.claude.reportAi.entities.PriceComparison;
import com.claude.reportAi.repository.PriceComparisonRepository;
import com.claude.reportAi.service.ModelChatClientFactory;
import com.claude.reportAi.service.TokenRateLimiter;
import com.claude.reportAi.service.estimate.PdfPageImageExtractor;
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
 * Processore asincrono per il confronto prezzi tra più offerte di fornitori.
 *
 * Pipeline in 3 fasi:
 *  1. Per ogni PDF: estrazione testo + immagini → chiamata LLM per strutturare l'offerta
 *  2. Confronto comparativo: tutti i JSON estratti → chiamata LLM per report di valutazione
 *  3. Generazione documento Word professionale
 *
 * Gestione limiti token:
 *  - Input per file: testo troncato a MAX_TEXT_PER_FILE caratteri (80% inizio + 20% fine)
 *  - Immagini: massimo MAX_IMAGES_PER_FILE pagine per file (prezzi catalogo, schemi tecnici)
 *  - Output estrazione per file: 4 000 token
 *  - Output confronto finale: 16 000 token
 *  - Rate limiting Anthropic via TokenRateLimiter (27 000 TPM)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PriceComparisonProcessor {

    private final PriceComparisonRepository repository;
    private final ModelChatClientFactory modelFactory;
    private final TokenRateLimiter tokenRateLimiter;
    private final PriceComparisonReportBuilder reportBuilder;
    private final PdfPageImageExtractor pdfPageImageExtractor;

    /** Caratteri massimi di testo per file prima del troncamento */
    private static final int MAX_TEXT_PER_FILE = 50_000;
    /** Numero massimo di immagini per file inviate al modello */
    private static final int MAX_IMAGES_PER_FILE = 3;

    // ─────────────────────────────────────────────────────────────────
    // System prompts
    // ─────────────────────────────────────────────────────────────────

    private static final String SYSTEM_EXTRACTION = """
            Sei un esperto analista di offerte commerciali nel settore oil & gas onshore.
            Analizza l'offerta di questo fornitore per la fornitura o noleggio di moduli, unità prefabbricate o attrezzature oil & gas.
            Estrai TUTTE le informazioni rilevanti: prezzi, specifiche tecniche, termini commerciali, garanzie, lead time.
            Se sono presenti immagini, analizzale attentamente per trovare: cataloghi prodotti, listini prezzi, tabelle tecniche, disegni schematici, specifiche dimensionali.
            Rispondi ESCLUSIVAMENTE con un oggetto JSON valido. Nessun markdown, nessun testo aggiuntivo prima o dopo.
            """;

    private static final String SYSTEM_COMPARISON = """
            Sei un responsabile acquisti senior con 20+ anni di esperienza nel settore oil & gas onshore/offshore.
            Il tuo cliente deve selezionare il miglior fornitore per la fornitura o noleggio di moduli oil & gas.
            Devi preparare una valutazione professionale, oggettiva e dettagliata per supportare la decisione finale.
            Valuta: prezzo, qualità tecnica, lead time, termini commerciali, affidabilità del fornitore, rischi.
            Assegna punteggi ponderati (0-100) per ogni criterio. Sii rigoroso e imparziale.
            La raccomandazione deve essere chiara, motivata e orientata all'interesse del cliente.
            Rispondi ESCLUSIVAMENTE con un oggetto JSON valido. Nessun markdown. Lingua: italiano.
            """;

    // ─────────────────────────────────────────────────────────────────
    // Punto di ingresso asincrono
    // ─────────────────────────────────────────────────────────────────

    @Async("priceComparisonExecutor")
    public void processAsync(UUID jobId, List<byte[]> fileContents, List<String> filenames, String model) {
        log.info("START confronto prezzi | jobId={} | numFiles={} | model={}", jobId, fileContents.size(), model);
        long startTime = System.currentTimeMillis();

        try {
            updateJob(jobId, PriceComparison.JobStatus.PROCESSING, 2, "Avvio elaborazione...");

            int total = fileContents.size();
            List<String> supplierJsons = new ArrayList<>();

            // ── FASE 1: Estrazione strutturata per ogni fornitore ──
            for (int i = 0; i < total; i++) {
                int progressStart = 5 + (i * 50 / total);
                int progressEnd   = 5 + ((i + 1) * 50 / total);
                String filename   = filenames.get(i);

                updateProgress(jobId, progressStart,
                        "Analisi offerta " + (i + 1) + "/" + total + ": " + filename);
                log.info("Estrazione dati fornitore {}/{}: {}", i + 1, total, filename);

                // Estrazione testo
                String fullText = extractText(fileContents.get(i));
                if (fullText == null || fullText.isBlank()) {
                    log.warn("Nessun testo estratto dal file: {}", filename);
                    fullText = "";
                }
                String truncatedText = truncateText(fullText, MAX_TEXT_PER_FILE);
                log.info("Testo file {}: {} car. originali → {} car. inviati",
                        filename, fullText.length(), truncatedText.length());

                // Estrazione immagini (solo per modelli Anthropic con visione)
                List<byte[]> images = new ArrayList<>();
                if (ModelChatClientFactory.isAnthropicModel(model)) {
                    try {
                        List<byte[]> allImages = pdfPageImageExtractor.extractPageImages(fileContents.get(i));
                        images = allImages.subList(0, Math.min(MAX_IMAGES_PER_FILE, allImages.size()));
                        log.info("Immagini estratte da {}: {}/{}", filename, images.size(), allImages.size());
                    } catch (Exception e) {
                        log.warn("Impossibile estrarre immagini da {}: {}", filename, e.getMessage());
                    }
                }

                // Chiamata LLM per estrazione strutturata
                updateProgress(jobId, (progressStart + progressEnd) / 2,
                        "Elaborazione AI offerta " + (i + 1) + "/" + total + " (" + filename + ")");

                String extractionPrompt = buildExtractionPrompt(filename, truncatedText, !images.isEmpty());
                int estimatedTokens = estimateTokens(extractionPrompt, images);

                if (ModelChatClientFactory.isAnthropicModel(model)) {
                    tokenRateLimiter.waitIfNeeded(estimatedTokens);
                }

                ChatResponse extractionResp = modelFactory.callWithImages(
                        model, SYSTEM_EXTRACTION, extractionPrompt, 4000, false, images);

                int actualTokens = extractActualTokens(extractionResp, estimatedTokens);
                if (ModelChatClientFactory.isAnthropicModel(model)) {
                    tokenRateLimiter.recordUsage(actualTokens);
                }

                String supplierJson = extractionResp.getResult().getOutput().getText();
                if (supplierJson == null || supplierJson.isBlank()) {
                    supplierJson = "{\"nome_fornitore\": \"" + filename + "\", \"errore\": \"Nessun dato estratto\"}";
                }
                supplierJsons.add(supplierJson);
                log.info("Dati estratti fornitore {}: {} car.", filename, supplierJson.length());

                updateProgress(jobId, progressEnd, "Offerta " + (i + 1) + "/" + total + " elaborata");
            }

            // ── FASE 2: Confronto comparativo ──
            updateProgress(jobId, 57,
                    "Elaborazione confronto tra " + total + " fornitori...");
            log.info("Avvio confronto comparativo tra {} fornitori", total);

            String comparisonPrompt = buildComparisonPrompt(supplierJsons, filenames);
            int compTokens = comparisonPrompt.length() / 3;

            if (ModelChatClientFactory.isAnthropicModel(model)) {
                tokenRateLimiter.waitIfNeeded(compTokens);
            }

            updateProgress(jobId, 63,
                    "Generazione valutazione comparativa con " + model + "...");

            ChatResponse compResponse = modelFactory.call(
                    model, SYSTEM_COMPARISON, comparisonPrompt, 16000, false);

            int actualCompTokens = extractActualTokens(compResponse, compTokens);
            if (ModelChatClientFactory.isAnthropicModel(model)) {
                tokenRateLimiter.recordUsage(actualCompTokens);
            }

            String comparisonJson = compResponse.getResult().getOutput().getText();
            log.info("JSON confronto ricevuto: {} car.",
                    comparisonJson != null ? comparisonJson.length() : 0);

            // ── FASE 3: Generazione documento Word ──
            updateProgress(jobId, 85, "Generazione documento Word professionale...");
            byte[] docx = reportBuilder.build(comparisonJson, filenames);

            // Salvataggio risultato
            String fileName = "confronto-offerte-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    + ".docx";

            PriceComparison job = loadJob(jobId);
            job.setStatus(PriceComparison.JobStatus.COMPLETED);
            job.setProgress(100);
            job.setCurrentStep("Confronto completato — " + total + " offerte analizzate");
            job.setResultFileName(fileName);
            job.setResultFileContent(docx);
            repository.save(job);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("END confronto prezzi | jobId={} | tempo={}s", jobId, elapsed / 1000);

        } catch (Exception e) {
            log.error("ERRORE confronto prezzi | jobId={}", jobId, e);
            try {
                PriceComparison job = loadJob(jobId);
                job.setStatus(PriceComparison.JobStatus.FAILED);
                job.setErrorMessage(truncate(e.getMessage(), 1000));
                job.setCurrentStep(truncate("Errore: " + e.getMessage(), 500));
                repository.save(job);
            } catch (Exception saveEx) {
                log.error("Impossibile salvare stato FAILED | jobId={}: {}", jobId, saveEx.getMessage());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Text extraction
    // ─────────────────────────────────────────────────────────────────

    private String extractText(byte[] pdfBytes) {
        TikaDocumentReader reader = new TikaDocumentReader(new ByteArrayResource(pdfBytes));
        List<Document> docs = reader.get();
        return docs.stream()
                .map(Document::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * Tronca il testo mantenendo l'80% iniziale e il 20% finale,
     * per preservare sia l'intestazione (dati fornitore, modelli) sia le appendici
     * (prezzi finali, condizioni).
     */
    private String truncateText(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) return text;
        int firstPart = (int) (maxChars * 0.80);
        int lastPart  = maxChars - firstPart;
        return text.substring(0, firstPart)
                + "\n\n[... TESTO TRONCATO PER LIMITI DI CONTESTO ...]\n\n"
                + text.substring(text.length() - lastPart);
    }

    // ─────────────────────────────────────────────────────────────────
    // Prompt builders
    // ─────────────────────────────────────────────────────────────────

    private String buildExtractionPrompt(String filename, String text, boolean hasImages) {
        StringBuilder sb = new StringBuilder();
        sb.append("File documento: ").append(filename).append("\n\n");

        if (hasImages) {
            sb.append("[NOTA: Sono allegate le immagini delle pagine principali del documento.\n");
            sb.append("Analizza attentamente cataloghi prodotti, listini prezzi, tabelle tecniche, disegni e schemi.]\n\n");
        }

        if (!text.isBlank()) {
            sb.append("[TESTO ESTRATTO DAL DOCUMENTO]\n");
            sb.append(text).append("\n\n");
        } else {
            sb.append("[Il documento non contiene testo estraibile. Analizza le immagini allegate.]\n\n");
        }

        sb.append("""
                Estrai TUTTE le informazioni rilevanti dell'offerta in questo formato JSON:
                {
                  "nome_fornitore": "...",
                  "riferimento_offerta": "...",
                  "data_offerta": "...",
                  "valuta_principale": "USD|EUR|...",
                  "moduli_offerti": [
                    {
                      "tipo": "...",
                      "codice": "...",
                      "descrizione": "...",
                      "quantita": 0,
                      "prezzo_unitario": "...",
                      "prezzo_totale": "...",
                      "dimensioni": "...",
                      "peso_kg": "...",
                      "specifiche_chiave": ["...", "..."],
                      "incluso": ["...", "..."],
                      "escluso": ["...", "..."],
                      "lead_time_settimane": "...",
                      "garanzia": "..."
                    }
                  ],
                  "riepilogo_economico": {
                    "subtotale": "...",
                    "sconti": "...",
                    "trasporto_installazione": "...",
                    "totale": "...",
                    "valuta": "...",
                    "note_prezzo": "..."
                  },
                  "termini_commerciali": {
                    "termini_pagamento": "...",
                    "condizioni_consegna": "...",
                    "validita_offerta": "...",
                    "incoterms": "...",
                    "penali_ritardo": "...",
                    "luogo_consegna": "..."
                  },
                  "specifiche_tecniche_generali": {
                    "standard_riferimento": ["...", "..."],
                    "certificazioni": ["...", "..."],
                    "materiali_principali": ["...", "..."],
                    "classe_pressione": "...",
                    "temperatura_operativa": "...",
                    "classificazione_area": "..."
                  },
                  "referenze_oil_gas": ["...", "..."],
                  "punti_distintivi": ["...", "..."],
                  "limitazioni_esclusioni": ["...", "..."],
                  "note_importanti": "..."
                }
                """);
        return sb.toString();
    }

    private String buildComparisonPrompt(List<String> supplierJsons, List<String> filenames) {
        StringBuilder sb = new StringBuilder();
        sb.append("[DATI STRUTTURATI ESTRATTI DALLE OFFERTE]\n\n");

        for (int i = 0; i < supplierJsons.size(); i++) {
            sb.append("=== FORNITORE ").append(i + 1)
              .append(" — ").append(filenames.get(i)).append(" ===\n");
            sb.append(supplierJsons.get(i)).append("\n\n");
        }

        sb.append("[ISTRUZIONI ANALISI]\n");
        sb.append("Confronta le ").append(supplierJsons.size()).append(" offerte in modo rigoroso.\n");
        sb.append("Per ogni criterio assegna un punteggio da 0 a 100 (100 = il migliore).\n");
        sb.append("Calcola i punteggi ponderati e il totale per ciascun fornitore.\n");
        sb.append("La raccomandazione finale deve essere chiara e motivata con dati concreti.\n\n");

        sb.append("""
                Restituisci ESCLUSIVAMENTE questo JSON (senza markdown):
                {
                  "titolo_progetto": "Valutazione Fornitori — Confronto Offerte Moduli Oil & Gas",
                  "data_valutazione": "GG/MM/AAAA",
                  "numero_fornitori": 0,
                  "executive_summary": "3-5 frasi concise per il top management con la raccomandazione chiave e i principali driver della scelta",
                  "tabella_comparativa": [
                    {
                      "criterio": "Prezzo Totale",
                      "peso_percentuale": 30,
                      "unita": "USD",
                      "valori_fornitori": [{"fornitore": "...", "valore": "...", "punteggio": 0, "note": "..."}]
                    },
                    {
                      "criterio": "Lead Time",
                      "peso_percentuale": 20,
                      "unita": "settimane",
                      "valori_fornitori": [{"fornitore": "...", "valore": "...", "punteggio": 0, "note": "..."}]
                    },
                    {
                      "criterio": "Qualità Tecnica",
                      "peso_percentuale": 25,
                      "unita": "punteggio/100",
                      "valori_fornitori": [{"fornitore": "...", "valore": "...", "punteggio": 0, "note": "..."}]
                    },
                    {
                      "criterio": "Termini Commerciali",
                      "peso_percentuale": 15,
                      "unita": "punteggio/100",
                      "valori_fornitori": [{"fornitore": "...", "valore": "...", "punteggio": 0, "note": "..."}]
                    },
                    {
                      "criterio": "Referenze e Affidabilità",
                      "peso_percentuale": 10,
                      "unita": "punteggio/100",
                      "valori_fornitori": [{"fornitore": "...", "valore": "...", "punteggio": 0, "note": "..."}]
                    }
                  ],
                  "analisi_fornitori": [
                    {
                      "nome": "...",
                      "rank": 1,
                      "score_totale": 0,
                      "punteggio_economico": 0,
                      "punteggio_tecnico": 0,
                      "punteggio_commerciale": 0,
                      "prezzo_totale_offerto": "...",
                      "lead_time": "...",
                      "conformita_requisiti": "CONFORME|PARZIALMENTE_CONFORME|NON_CONFORME",
                      "punti_di_forza": ["...", "..."],
                      "punti_di_debolezza": ["...", "..."],
                      "rischi_principali": ["...", "..."],
                      "opportunita_negoziazione": ["...", "..."],
                      "note_tecniche": "..."
                    }
                  ],
                  "matrice_valutazione": [
                    {
                      "fornitore": "...",
                      "criteri": [
                        {"nome": "Prezzo", "peso": 30, "punteggio": 0, "ponderato": 0.0},
                        {"nome": "Lead Time", "peso": 20, "punteggio": 0, "ponderato": 0.0},
                        {"nome": "Qualità Tecnica", "peso": 25, "punteggio": 0, "ponderato": 0.0},
                        {"nome": "Termini Commerciali", "peso": 15, "punteggio": 0, "ponderato": 0.0},
                        {"nome": "Referenze", "peso": 10, "punteggio": 0, "ponderato": 0.0}
                      ],
                      "totale_ponderato": 0.0,
                      "rank": 1
                    }
                  ],
                  "analisi_rischi": [
                    {
                      "fornitore": "...",
                      "rischio": "...",
                      "impatto": "ALTO|MEDIO|BASSO",
                      "probabilita": "ALTA|MEDIA|BASSA",
                      "mitigazione": "..."
                    }
                  ],
                  "raccomandazione": {
                    "fornitore_consigliato": "...",
                    "motivazione": "...",
                    "saving_stimato_vs_media": "...",
                    "condizioni_per_accettazione": ["...", "..."],
                    "punti_da_negoziare": ["...", "..."],
                    "fornitore_alternativo": "...",
                    "motivazione_alternativo": "..."
                  },
                  "note_finali": "..."
                }
                """);
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────

    private int estimateTokens(String prompt, List<byte[]> images) {
        int textTokens  = prompt.length() / 3;
        // Ogni immagine a 150 DPI ≈ 800x600 px ≈ ~1500 token stimati
        int imageTokens = images.size() * 1500;
        return textTokens + imageTokens;
    }

    private void updateJob(UUID jobId, PriceComparison.JobStatus status, int progress, String step) {
        PriceComparison job = loadJob(jobId);
        job.setStatus(status);
        job.setProgress(progress);
        job.setCurrentStep(truncate(step, 500));
        repository.save(job);
    }

    private void updateProgress(UUID jobId, int progress, String step) {
        PriceComparison job = loadJob(jobId);
        job.setProgress(progress);
        job.setCurrentStep(truncate(step, 500));
        repository.save(job);
    }

    private PriceComparison loadJob(UUID jobId) {
        return repository.findById(jobId)
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
