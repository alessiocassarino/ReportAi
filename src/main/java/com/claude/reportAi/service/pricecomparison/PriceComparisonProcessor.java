package com.claude.reportAi.service.pricecomparison;

import com.claude.reportAi.entities.PriceComparison;
import com.claude.reportAi.exception.JobCancelledException;
import com.claude.reportAi.repository.PriceComparisonRepository;
import com.claude.reportAi.service.ModelChatClientFactory;
import com.claude.reportAi.service.PromptTemplateService;
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
    private final PromptTemplateService promptTemplateService;

    /** Caratteri massimi di testo per file prima del troncamento */
    private static final int MAX_TEXT_PER_FILE = 50_000;
    /** Numero massimo di immagini per file in condizioni normali */
    private static final int MAX_IMAGES_PER_FILE = 5;
    /** Soglia (chars) sotto la quale si attiva il fallback visuale (es. PDF da PPTX, scansioni) */
    private static final int LOW_TEXT_THRESHOLD = 500;
    /** Numero massimo di immagini quando il testo è scarso (fallback visuale) */
    private static final int MAX_IMAGES_PER_FILE_FALLBACK = 10;

    // System prompts are loaded from DB via PromptTemplateService at job start

    // ─────────────────────────────────────────────────────────────────
    // Punto di ingresso asincrono
    // ─────────────────────────────────────────────────────────────────

    @Async("priceComparisonExecutor")
    public void processAsync(UUID jobId, List<byte[]> fileContents, List<String> filenames, String model) {
        log.info("START confronto prezzi | jobId={} | numFiles={} | model={}", jobId, fileContents.size(), model);
        long startTime = System.currentTimeMillis();

        try {
            String sector = loadJob(jobId).getSector();
            final String systemExtraction = promptTemplateService.resolve("price-comparison-extraction", sector);
            final String systemComparison = promptTemplateService.resolve("price-comparison-synthesis", sector);
            final String extractionUserTemplate = promptTemplateService.resolve("price-comparison-extraction-user", sector);
            final String synthesisUserTemplate = promptTemplateService.resolve("price-comparison-synthesis-user", sector);

            updateJob(jobId, PriceComparison.JobStatus.PROCESSING, 2, "Avvio elaborazione...");

            int total = fileContents.size();
            List<String> supplierJsons = new ArrayList<>();
            List<String> debugEntries  = new ArrayList<>();

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
                log.info("Testo estratto da '{}': {} car. originali → {} car. inviati al modello",
                        filename, fullText.length(), truncatedText.length());
                log.debug("Anteprima testo estratto da '{}' (primi 500 chars):\n{}",
                        filename,
                        truncatedText.isEmpty() ? "<VUOTO>"
                                : truncatedText.substring(0, Math.min(500, truncatedText.length())));

                // Fallback visuale: PDF da PPTX o scansioni hanno testo scarso o assente.
                // In questi casi si aumenta il numero di immagini inviate al modello per
                // compensare la mancanza di testo estraibile (es. prezzi su slide grafiche).
                boolean isLowText = fullText.length() < LOW_TEXT_THRESHOLD;
                int imageLimit = isLowText ? MAX_IMAGES_PER_FILE_FALLBACK : MAX_IMAGES_PER_FILE;
                if (isLowText) {
                    log.info("Testo scarso da {} ({} chars) — attivato fallback visuale con max {} immagini",
                            filename, fullText.length(), imageLimit);
                }

                // Estrazione immagini per modelli con visione (Anthropic e Gemini)
                List<byte[]> images = new ArrayList<>();
                if (ModelChatClientFactory.isAnthropicModel(model) || ModelChatClientFactory.isGeminiModel(model)) {
                    try {
                        List<byte[]> allImages = pdfPageImageExtractor.extractPageImages(fileContents.get(i));
                        // Distribuzione uniforme: prima pagina + pagine equidistanti + ultima,
                        // così le slide centrali (riepilogo economico, condizioni pagamento)
                        // non vengono mai saltate come avveniva con la strategia "prima + ultime".
                        images = selectRepresentativeImages(allImages, imageLimit);
                        log.info("Immagini selezionate da {}: {}/{} (distribuzione uniforme{})",
                                filename, images.size(), allImages.size(),
                                isLowText ? ", fallback visuale attivo" : "");
                    } catch (Exception e) {
                        log.warn("Impossibile estrarre immagini da {}: {}", filename, e.getMessage());
                    }
                }

                // Chiamata LLM per estrazione strutturata
                throwIfCancelled(jobId);
                updateProgress(jobId, (progressStart + progressEnd) / 2,
                        "Elaborazione AI offerta " + (i + 1) + "/" + total + " (" + filename + ")");

                String extractionPrompt = buildExtractionPrompt(filename, truncatedText, !images.isEmpty(), extractionUserTemplate);
                log.info("Prompt estrazione '{}': {} caratteri (~{} token) | {} immagini",
                        filename, extractionPrompt.length(), estimateTokens(extractionPrompt, images), images.size());
                log.debug("Prompt estrazione '{}' completo:\n{}", filename, extractionPrompt);
                int estimatedTokens = estimateTokens(extractionPrompt, images);

                if (ModelChatClientFactory.isAnthropicModel(model)) {
                    tokenRateLimiter.waitIfNeeded(estimatedTokens);
                }

                ChatResponse extractionResp = modelFactory.callWithImages(
                        model, systemExtraction, extractionPrompt, 8000, false, images);

                int actualTokens = extractActualTokens(extractionResp, estimatedTokens);
                if (ModelChatClientFactory.isAnthropicModel(model)) {
                    tokenRateLimiter.recordUsage(actualTokens);
                }

                String rawSupplierJson = extractionResp.getResult().getOutput().getText();
                log.info("Risposta estrazione '{}': {} caratteri | token: {}",
                        filename, rawSupplierJson != null ? rawSupplierJson.length() : 0, actualTokens);
                if (rawSupplierJson == null || rawSupplierJson.isBlank()) {
                    rawSupplierJson = "{\"nome_fornitore\": \"" + filename + "\", \"errore\": \"Nessun dato estratto\"}";
                }

                // Pulizia markdown + detection troncamento + repair best-effort.
                // Eseguita PRIMA di passare il JSON al secondo LLM: un JSON malformato
                // causa hallucination dei campi mancanti nel confronto comparativo.
                String supplierJson = cleanSupplierJson(rawSupplierJson, filename, jobId);
                supplierJsons.add(supplierJson);

                log.debug("supplierJson pulito per '{}' ({} chars):\n{}", filename, supplierJson.length(), supplierJson);

                // Raccoglie l'entry di debug per persistenza su DB
                String textPreview = truncatedText.isEmpty() ? ""
                        : truncatedText.substring(0, Math.min(300, truncatedText.length()));
                debugEntries.add(buildDebugEntry(filename, fullText.length(), isLowText,
                        images.size(), textPreview, supplierJson));

                updateProgress(jobId, progressEnd, "Offerta " + (i + 1) + "/" + total + " elaborata");
            }

            // Persiste i supplierJson grezzi subito dopo la Fase 1:
            // se la Fase 2 fallisce, i dati di estrazione per file sono già visibili nel DB.
            {
                PriceComparison jobDebug = loadJob(jobId);
                jobDebug.setDebugSupplierJsons("[" + String.join(",", debugEntries) + "]");
                repository.save(jobDebug);
                log.info("supplierJsons persistiti su DB | job={} | file={}", jobId, total);
            }

            // ── FASE 2: Confronto comparativo ──
            throwIfCancelled(jobId);
            updateProgress(jobId, 57,
                    "Elaborazione confronto tra " + total + " fornitori...");
            log.info("Avvio confronto comparativo tra {} fornitori", total);

            String comparisonPrompt = buildComparisonPrompt(supplierJsons, filenames, synthesisUserTemplate);
            int compTokens = comparisonPrompt.length() / 3;

            log.info("Prompt confronto comparativo: {} caratteri (~{} token) | {} fornitori | modello={}",
                    comparisonPrompt.length(), compTokens, total, model);
            log.debug("Prompt confronto completo:\n{}", comparisonPrompt);

            if (ModelChatClientFactory.isAnthropicModel(model)) {
                tokenRateLimiter.waitIfNeeded(compTokens);
            }

            updateProgress(jobId, 63,
                    "Generazione valutazione comparativa con " + model + "...");

            ChatResponse compResponse = modelFactory.call(
                    model, systemComparison, comparisonPrompt, 16000, false);

            int actualCompTokens = extractActualTokens(compResponse, compTokens);
            if (ModelChatClientFactory.isAnthropicModel(model)) {
                tokenRateLimiter.recordUsage(actualCompTokens);
            }

            String comparisonJson = compResponse.getResult().getOutput().getText();
            log.info("Risposta confronto comparativo: {} caratteri | token: {}",
                    comparisonJson != null ? comparisonJson.length() : 0,
                    extractActualTokens(compResponse, compTokens));
            log.debug("JSON confronto completo:\n{}", comparisonJson);

            // Persiste il comparisonJson grezzo: permette di confrontarlo con il DOCX finale
            // e capire se i dati si perdono nel merge LLM o nel ReportBuilder.
            {
                PriceComparison jobDebug = loadJob(jobId);
                jobDebug.setDebugComparisonJson(comparisonJson);
                repository.save(jobDebug);
                log.info("comparisonJson persistito su DB | job={}", jobId);
            }

            // ── FASE 3: Generazione documento Word ──
            updateProgress(jobId, 85, "Generazione documento Word professionale...");
            byte[] docx = reportBuilder.build(comparisonJson, filenames);

            // Salvataggio risultato
            PriceComparison job = loadJob(jobId);
            String fileName = job.getResultFileName() != null
                    ? job.getResultFileName()
                    : "confronto-offerte-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".docx";

            job.setStatus(PriceComparison.JobStatus.COMPLETED);
            job.setProgress(100);
            job.setCurrentStep("Confronto completato — " + total + " offerte analizzate");
            job.setResultFileName(fileName);
            job.setResultFileContent(docx);
            repository.save(job);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("END confronto prezzi | jobId={} | tempo={}s", jobId, elapsed / 1000);

        } catch (JobCancelledException e) {
            log.info("Job annullato dall'utente | jobId={}", jobId);
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
     * Tronca il testo mantenendo il 60% iniziale e il 40% finale.
     * Lo split 60/40 (anziché 80/20) dà più peso alla parte finale del documento,
     * dove si trovano tipicamente prezzi totali, condizioni di pagamento e validità
     * dell'offerta — informazioni critiche spesso tagliate con lo split 80/20.
     */
    private String truncateText(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) return text;
        int firstPart = (int) (maxChars * 0.60);
        int lastPart  = maxChars - firstPart;
        return text.substring(0, firstPart)
                + "\n\n[... TESTO TRONCATO PER LIMITI DI CONTESTO ...]\n\n"
                + text.substring(text.length() - lastPart);
    }

    // ─────────────────────────────────────────────────────────────────
    // Prompt builders
    // ─────────────────────────────────────────────────────────────────

    private String buildExtractionPrompt(String filename, String text, boolean hasImages, String userTemplate) {
        StringBuilder sb = new StringBuilder();
        sb.append("File documento: ").append(filename).append("\n\n");

        // Avviso esplicito per documenti originati da presentazioni PowerPoint:
        // questi PDF hanno spesso testo scarso o assente perché le slide vengono
        // renderizzate graficamente — in tal caso l'analisi visiva è quella primaria.
        if (filename.toLowerCase().contains("pptx")) {
            sb.append("[NOTA IMPORTANTE: Questo documento è stato generato da una presentazione PowerPoint.\n");
            sb.append("Il testo potrebbe essere parziale o assente perché le slide sono renderizzate graficamente.\n");
            sb.append("Affidati principalmente alle IMMAGINI ALLEGATE per estrarre tutte le informazioni,\n");
            sb.append("inclusi prezzi totali, condizioni di pagamento, validità offerta e termini commerciali.]\n\n");
        }

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

        sb.append(userTemplate);
        return sb.toString();
    }

    private String buildComparisonPrompt(List<String> supplierJsons, List<String> filenames, String userTemplate) {
        StringBuilder sb = new StringBuilder();
        sb.append("[DATI STRUTTURATI ESTRATTI DALLE OFFERTE]\n\n");

        for (int i = 0; i < supplierJsons.size(); i++) {
            sb.append("=== FORNITORE ").append(i + 1)
              .append(" — ").append(filenames.get(i)).append(" ===\n");
            sb.append(supplierJsons.get(i)).append("\n\n");
        }

        sb.append("[STEP 1 — IDENTIFICAZIONE FORNITORI UNICI — OBBLIGATORIO]\n");
        sb.append("Hai ricevuto ").append(supplierJsons.size())
          .append(" JSON estratti da altrettanti file.\n");
        sb.append("I file potrebbero contenere documenti MULTIPLI dello STESSO fornitore\n");
        sb.append("(es. offerta economica + specifiche tecniche + listino prezzi della stessa azienda).\n");
        sb.append("Prima di confrontare: leggi il campo 'nome_fornitore' di ogni JSON e raggruppa per azienda.\n");
        sb.append("Se più JSON appartengono alla stessa azienda, UNISCI i dati (scegli i valori più completi).\n");
        sb.append("Imposta 'numero_fornitori' al numero di fornitori UNICI (può essere < numero di file).\n\n");

        sb.append("[STEP 2 — CONFRONTO]\n");
        sb.append("Confronta i fornitori UNICI identificati in modo rigoroso.\n");
        sb.append("Per ogni criterio assegna un punteggio da 0 a 100 (100 = il migliore).\n");
        sb.append("Calcola i punteggi ponderati e il totale per ciascun fornitore unico.\n");
        sb.append("La raccomandazione finale deve essere chiara e motivata con dati concreti.\n\n");

        sb.append(userTemplate);
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────

    /**
     * Seleziona le pagine più significative per l'analisi commerciale usando
     * una distribuzione uniforme sull'intero documento.
     *
     * Strategia "distribuzione uniforme":
     *  - k=0             : prima pagina (header, dati fornitore)
     *  - k=1..maxImages-2: pagine equidistanti (riepilogo economico, slide centrali)
     *  - k=maxImages-1   : ultima pagina (condizioni pagamento, firme, validità)
     *
     * Formula: idx = k * (n-1) / (maxImages-1)
     * Garantisce sempre prima e ultima pagina, distribuendo uniformemente le
     * pagine centrali — così le slide con prezzi a metà documento non vengono
     * mai saltate come accadeva con la vecchia strategia "prima + ultime N".
     */
    private List<byte[]> selectRepresentativeImages(List<byte[]> allImages, int maxImages) {
        if (allImages.isEmpty()) return allImages;
        if (allImages.size() <= maxImages) return allImages;

        int n = allImages.size();
        List<byte[]> selected = new ArrayList<>(maxImages);
        for (int k = 0; k < maxImages; k++) {
            int idx = k * (n - 1) / (maxImages - 1);
            selected.add(allImages.get(idx));
        }
        return selected;
    }

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

    private void throwIfCancelled(UUID jobId) {
        if (loadJob(jobId).getStatus() == PriceComparison.JobStatus.CANCELLED) {
            throw new JobCancelledException(jobId);
        }
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

    /**
     * Pulisce e ripara il supplierJson restituito dal modello di estrazione.
     *
     * Problemi gestiti:
     *  1. Markdown code-block (```json ... ```) — alcuni modelli li aggiungono
     *     nonostante il prompt dica "nessun markdown"
     *  2. JSON troncato — avviene quando il numero di moduli è elevato e la
     *     risposta supera il limite max_tokens; senza riparazione il secondo LLM
     *     riceve JSON invalido e hallucina i campi mancanti (prezzi, termini)
     *
     * La riparazione è best-effort: chiude parentesi e virgolette aperte.
     * I campi troncati risulteranno assenti nel JSON riparato — questo è corretto
     * perché il secondo LLM li tratterà come "non specificati" anziché inventarli.
     */
    private String cleanSupplierJson(String raw, String filename, UUID jobId) {
        if (raw == null || raw.isBlank()) return raw;

        // 1. Rimuovi markdown code-block
        String s = raw.strip();
        if (s.startsWith("```")) {
            s = s.replaceAll("(?s)^```[a-zA-Z]*\\n?", "").replaceAll("```\\s*$", "").strip();
        }

        // 2. Trova l'inizio del JSON
        int start = s.indexOf('{');
        if (start < 0) {
            log.warn("supplierJson per '{}': nessun oggetto JSON trovato nella risposta LLM | job={}", filename, jobId);
            return "{\"nome_fornitore\": \"" + filename + "\", \"errore\": \"JSON non trovato nella risposta LLM\"}";
        }
        s = s.substring(start);

        // 3. Detection troncamento: conta parentesi aperte vs chiuse
        long opens  = s.chars().filter(c -> c == '{').count();
        long closes = s.chars().filter(c -> c == '}').count();
        long arrO   = s.chars().filter(c -> c == '[').count();
        long arrC   = s.chars().filter(c -> c == ']').count();
        boolean truncated = (opens != closes) || (arrO != arrC);

        if (!truncated) return s;

        // 4. JSON troncato — logga warning e ripara
        log.warn("supplierJson TRONCATO per '{}': {}x'{{' vs {}x'}}', {}x'[' vs {}x']' — riparazione best-effort. "
                + "Causa: max_tokens superato. Campi troncati saranno assenti nel confronto finale | job={}",
                filename, opens, closes, arrO, arrC, jobId);

        StringBuilder sb = new StringBuilder(s.stripTrailing());
        // Rimuovi virgola finale spuria (non valida come ultimo token JSON)
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) == ',') {
            sb.deleteCharAt(sb.length() - 1);
        }
        // Chiudi stringa aperta (conteggio grezzo — non tiene conto di \")
        long quotes = s.chars().filter(c -> c == '"').count();
        if (quotes % 2 != 0) sb.append('"');
        // Chiudi array e oggetti aperti
        for (long i = 0; i < arrO - arrC; i++) sb.append(']');
        for (long i = 0; i < opens - closes; i++) sb.append('}');
        return sb.toString();
    }

    /**
     * Costruisce una entry JSON di debug per un singolo file elaborato.
     * Il supplierJson è già JSON valido, quindi viene incorporato direttamente
     * come valore (non come stringa) per mantenere la struttura navigabile.
     */
    private String buildDebugEntry(String filename, int originalChars, boolean isLowText,
                                   int imagesCount, String textPreview, String supplierJson) {
        return "{\"file\":" + toJsonString(filename)
                + ",\"originalChars\":" + originalChars
                + ",\"lowText\":" + isLowText
                + ",\"images\":" + imagesCount
                + ",\"textPreview\":" + toJsonString(textPreview)
                + ",\"supplierJson\":" + supplierJson
                + "}";
    }

    /**
     * Wrappa una stringa Java in una stringa JSON con escaping minimo.
     * Usato solo per i campi di debug — non per dati esposti all'utente.
     */
    private String toJsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t")
               + "\"";
    }
}
