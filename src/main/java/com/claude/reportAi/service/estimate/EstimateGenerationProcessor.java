package com.claude.reportAi.service.estimate;

import com.claude.reportAi.entities.Estimate;
import com.claude.reportAi.exception.JobCancelledException;
import com.claude.reportAi.repository.EstimateRepository;
import com.claude.reportAi.service.ExchangeRateService;
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
import java.time.Year;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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
    private final ExchangeRateService exchangeRateService;

    private static final int TAVILY_CONTENT_MAX_CHARS = 400;

    private static final String SYSTEM_PROMPT = """
            RUOLO
            Cost estimator/tendering manager senior, 30 anni esperienza EPC oil & gas onshore (pipeline + impianti).
            INPUT: Scope of Work + Schedule + documenti tecnici.
            OUTPUT: JSON valido (schema nel messaggio utente). Lingua italiana. Valuta EUR. Usa il cambio EUR/USD fornito nel messaggio utente per cambio_eur_usd e per convertire benchmark espressi in USD; nessun valore in USD nel JSON finale.

            ═══ FASE 1 — SCOPE CHECK ═══
            Per ognuna delle 8 voci A-H assegna stato {INCLUSO|PARZIALE|ESCLUSO|INCERTO}:
            A. ENGINEERING (basic/FEED/detail/iso/P&ID)
            B. PROCUREMENT (line pipe, valvole, equipment, compressori, skid, bulk, cavi, strutture)
            C. COSTRUZIONE PIPELINE MECCANICA (linea+tie-in+collaudo+FJC)
            D. COSTRUZIONE PIPELINE CIVILE (scavo+rinterro+ripristini) — se ESCLUSO: rischio standby in rischi_principali con impatto_eur in EUR/gg
            E. ATTRAVERSAMENTI SPECIALI (HDD/TOC/microtunnel/spingitubo/cielo aperto)
            F. INSTALLAZIONE E&I (cabling, FOC, PC, SCADA)
            G. STAZIONI BVS/SCRAPER (dimensiona per pollici/m²/m³ cls/peso)
            H. STAZIONI COMPRESSION/METERING (dimensiona per pollici/m²/m³ cls/peso)
            Mai chiedere chiarimenti. Se INCERTO: assumi l'ipotesi più ragionevole, popola assumptions_taken e imposta estimate_status="ESTIMATE_WITH_ASSUMPTIONS".

            ═══ FASE 2 — GERARCHIA FONTI (vincolante) ═══
            Priorità: (1) file aziendali → (2) storici aziendali → (3) benchmark mercato → (4) assunzioni standard.
            File aziendali prevalgono SEMPRE su benchmark, sia per costi sia per rese:
            - Mai sostituire un prezzo interno con un prezzo di mercato
            - Mai sostituire una resa aziendale con una resa benchmark
            - Voci parzialmente coperte: dato aziendale come base, integra il mancante con mercato
            - Se i file aziendali contengono rese per diametro/terreno/tecnica, usa la più pertinente, non una media generica
            - Per ogni voce benchmark documenta in assunzioni: quale, range, perché applicabile

            ═══ FASE 3 — REGOLE DI CALCOLO ═══
            • Ammortamento mezzi: pro-rata su settimane di EFFETTIVO utilizzo, non su durata totale
            • Fattore utilizzo carburante: 55-65% se spread <700 m/gg | 75-80% se >700 m/gg | 50-60% mezzi yard
            • Sideboom+paywelder dimensionati per linea + tie-in in parallelo. Rif 48": 1 saldatura tie-in/gg = 1 paywelder + 1 escavatore + 2 sideboom. Diametri minori: scala proporzionalmente. Senza tie-in: dimensiona solo per la linea
            • Costi diretti SENZA contingency/margini (buffer solo in voce VI)
            • Quantità solo se documentate. Senza dato: HDD/TOC 1 ogni 15-25 km, spingitubo 3 ogni 15 km (~30 m/cad). Microtunnel SOLO se esplicito con conci cls. Senza base, non inserire la voce
            • Durata = lunghezza / (n_spread × resa × gg_lavorativi/mese). Mai gonfiare
            • analisi_dettaglio: SEMPRE due categorie separate "III.a - Forniture materiali progetto" e "III.b - Subappalti"
            • Stazioni (BVS, SS, LS, compressione): prima dati aziendali, poi dimensiona per pollici/m²/m³/peso effettivi senza sovrastimare

            ═══ FASE 4 — BENCHMARK (EUR; converti USD usando il tasso fornito nel messaggio utente) ═══

            EPC TOTALE EUR/km per zona × diametro (42-48" | 24-36" | 8-20"):
            Pianura semplice    1,5-3,2M | 1,2-2,6M | 0,8-1,5M
            Pianura agricola    1,8-3,5M | +15%     | +10%
            Collinare           2,8-4,2M | 1,9-3,2M | 1,2-2,2M
            Montuoso EU         3,5-5,5M
            Montuoso estremo    5,2-7,2M | 3,1-5,5M | 1,8-3,0M
            Artico              6,4-9,6M
            Giungla/palude      5,6-8,0M
            EUR/inch-metro 42-48": pianura 40-60 | collinare 60-95 | montuoso 84-148

            LINE PIPE X70 EXW (CIF EU 2025-26): scaling 1,2-1,5 EUR/kg.
            48"WT22 580-730 EUR/m | 42"WT20 480-620 | 36"WT17 380-520 | 24"WT12 200-280 | 16"WT9 130-190

            VALVOLE BALL CL600 c/attuatore (k EUR/pz):
            48" 480-720 | 36" 320-480 | 24" 160-250 | 16" 90-150

            COMPRESSORE 20-30 MW: 25-45 M EUR (~1 M EUR/MW pkg compressori; aggiungi accessori centrale)

            ATTRAVERSAMENTI EUR/m (rif 48", scala per diametri minori; precedenza prezzi aziendali):
            Spingitubo pianura 800-1.000 | HDD pianura 2.500-5.500 | HDD montagna 5.000-9.000 | Microtunnel cls 10.000-15.000

            NDT (100% RX+AUT H2-ready): tie-in/manuale 100-150 EUR/giunto | linea ~2.000 EUR/gg/spread

            PROTEZIONE CATODICA: 20-35 kEUR/km | FOC+HDPE: 40-65 kEUR/km
            DEG (Eng dettaglio): max 2% totale o ~75 EUR/ora stimata
            CAMP BASE 300 pers: 1-3 M EUR (se no hotel; precedenza aziendali)
            VITTO: operai rurali EU 40-55 EUR/p/gg | staff hotel 60-90 EUR/p/gg | 26 gg/mese
            TOTALE V (vitto/alloggio): 2-4% costo totale

            STANDBY: spread completo 190-250 kEUR/gg | parziale (saldatura) 100-120 | camp+indiretti 35-55

            SECURITY (% costo totale):
            Basso (EU/NA) 0,5-1% | Medio (LATAM stabile, SE Asia) 1,5-3% | Alto (MX nord, AfSubSah, MO) 3-6% | Estremo (conflitto) 6-12% + K&R + PV

            ═══ FASE 5 — QUADRO ECONOMICO ═══
            I. Mob+Temp Facilities | II. Costruzione (mezzi+pers+carburante)
            III. Subcontratti+Forniture (split III.a Forniture | III.b Subappalti)
            IV. Indiretti | V. Vitto/Alloggio
            SUBTOTALE I-V
            VI. su subtotale I-V: VI.a OH 6% + VI.b Contingency 2% + VI.c Assic/Finanziari 3-5% (totale tipico 12%)
            VII. COSTO TOTALE = (I-V) + VI
            VIII. PREZZO = VII × (1 + margine 6-10%, tipico 8%)

            ═══ FASE 6 — REPORT NARRATIVO ═══
            - executive_summary: max 3 frasi per top management (configurazione, prezzo, EUR/km, posizionamento benchmark)
            - Per ogni voce: documenta fonte_dato (AZIENDALE/BENCHMARK/ASSUNZIONE), rese, quantità, logica di calcolo
            - rischi_principali: prioritizzati con livello ALTO/MEDIO/BASSO e impatto_eur quantificato in EUR
            - cronoprogramma_sintetico: coerente con n_spread × durata × resa
            - sensitivity_analysis: ≥5 scenari (base, pessimistico -15%, ottimistico +10%, cambio ±10%, rese -10%)
            - raccomandazioni_contrattuali: ≥3 specifiche (clausole, penali, garanzie, risk allocation)

            ═══ FASE 7 — CHECK ANTI-SOVRASTIMA E COERENZA (eseguire prima di emettere) ═══
            SCOPE: scope_check su tutte 8 voci A-H; se civile=ESCLUSO rischio standby in EUR/gg; se engineering=INCLUSO DEG in III cap 2%; se procurement=ESCLUSO no line pipe/valvole in III; se attraversamenti=solo meccanica no HDD/TOC/microtunnel.
            NUMERICI: Σ analitica I-V = subtotale ±2%; EUR/km finale entro benchmark zona ±25%; EUR/inch-m entro benchmark ±25%; ripartizione I-V (Mob 3-5% | Costruz 30-40% | Forniture 40-55% | Indir 6-10% | Vitto 4-7%); contingency UNA sola volta; margine su costo post-contingency; n_spread × durata × resa = lunghezza ±5%; durata totale ≤ Gantt; no "Varie e imprevisti" >3% per capitolo.
            ANTI-SOVRASTIMA: prezzi/rese aziendali prevalgono sempre su benchmark; no doppie maggiorazioni (buffer solo in VI); no coefficienti prudenziali impliciti nelle rese; quantità solo se documentate; cap mensile spread non oltre benchmark salvo giustificazione aziendale; line pipe EUR/m coerente con 1,2-1,5 EUR/kg sul peso del tubo; fattore carburante coerente con resa spread; ammortamento = settimane utilizzo effettivo.
            COMPLETEZZA: assumptions_taken popolato per ogni dato non da file aziendali; sensitivity ≥5 scenari incluso base; raccomandazioni ≥3; tutti importi in EUR.
            Se un check fallisce, correggi prima di emettere.

            ═══ OUTPUT ═══
            JSON valido, nessun testo prima/dopo, no markdown. Campi obbligatori: scope_check (8 voci A-H), estimate_status, assumptions_taken, executive_summary, cambio_eur_usd (valore fornito nel messaggio utente), quadro_economico (I-VIII + subtotale; percentuali opzionali), kpi (derivati opzionali/null), benchmark_comparison, analisi_dettaglio (incluso III.a e III.b separati), rischi_principali (con impatto_eur), sensitivity_analysis (≥5), raccomandazioni_contrattuali (≥3), cronoprogramma_sintetico, note_finali.
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
            throwIfCancelled(jobId);
            updateProgress(jobId, 10, "Analisi struttura progetto");
            ProjectInfoExtractor.ProjectInfo info = projectInfoExtractor.extract(fullText, model);
            log.info("Info estratte: nazione={}, tipo={}, km={}, mesi={}",
                    info.nazione(), info.tipoProgetto(), info.lunghezzaKm(), info.durataMesi());

            // Step 3 – Recupero prezzi interni
            updateProgress(jobId, 25, "Recupero prezzi interni aziendali");
            String internalPricing = internalPricingRetriever.retrieveContext(info);
            log.info("Prezzi interni (vector store): {} caratteri (~{} token stimati)",
                    internalPricing != null ? internalPricing.length() : 0,
                    internalPricing != null ? internalPricing.length() / 3 : 0);
            log.debug("Vector store — contenuto completo (non incluso nel report):\n{}", internalPricing);

            // Step 4 – Ricerche web (7 categorie, progress 35→65)
            String paese = info.nazione() != null ? info.nazione() : "N/D";
            String tipo = info.tipoProgetto() != null ? info.tipoProgetto().toLowerCase() : "pipeline";

            int currentYear = Year.now().getValue();
            int previousYear = currentYear - 1;


            List<Map.Entry<String, String>> searchCategories = List.of(
                    Map.entry("MATERIALI E CONSUMABILI",
                            tipo + " pipeline pipe valves fittings welding electrodes fuel diesel cement steel "
                                    + paese + " material cost " + currentYear + " " + previousYear + " USD"),
                    Map.entry("COSTI DI MOBILIZZAZIONE DALL'ITALIA",
                            "heavy equipment mobilization Italy " + paese + " transport logistics cost " + currentYear),
                    Map.entry("BASI LOGISTICHE E ACCOMMODATION",
                            "labor camp accommodation catering oil gas " + paese + " daily rate USD person " + currentYear),
                    Map.entry("COSTI SICUREZZA",
                            "security services requirements oil gas construction " + paese + " cost " + currentYear),
                    Map.entry("COSTO DELLA MANODOPERA",
                            paese + " pipeline construction worker salary daily rate USD " + currentYear + " local expat")
            );

            Map<String, List<WebSearchService.SearchResult>> searchResults = new LinkedHashMap<>();
            int searchTotal = searchCategories.size();
            for (int i = 0; i < searchTotal; i++) {
                Map.Entry<String, String> entry = searchCategories.get(i);
                String categoria = entry.getKey();
                String query = entry.getValue();

                throwIfCancelled(jobId);
                int progress = 35 + (int) ((i / (double) searchTotal) * 30); // 35→65
                updateProgress(jobId, progress, "Ricerca: " + categoria);

                List<WebSearchService.SearchResult> results = webSearchService.search(query);
                log.info("Ricerca web [{}/{}] '{}': {} risultati | query='{}'",
                        i + 1, searchTotal, categoria, results.size(), query);
                log.debug("Ricerca web '{}' — risultati completi:\n{}", categoria, formatResultsForLog(results));
                searchResults.put(categoria, results);
            }

            List<byte[]> pageImages = List.of();
            if (ModelChatClientFactory.isAnthropicModel(model) || ModelChatClientFactory.isGeminiModel(model)) {
                updateProgress(jobId, 67, "Preparazione tavole visuali dal documento");
                pageImages = pdfPageImageExtractor.extractPageImages(pdfBytes);
                if (!pageImages.isEmpty()) {
                    log.info("Invio {} tavole visuali PDF al modello per lettura Gantt/grafici", pageImages.size());
                }
            }

            throwIfCancelled(jobId);
            updateProgress(jobId, 70, "Generazione preventivo con " + model);
            double usdPerEurRate = exchangeRateService.currentUsdPerEur();
            String userPrompt = buildUserPrompt(info, internalPricing, searchResults, !pageImages.isEmpty(), usdPerEurRate);

            log.info("Prompt inviato al modello: {} caratteri (~{} token stimati) | {} immagini | modello={} | cache={}",
                    userPrompt.length(), userPrompt.length() / 3, pageImages.size(), model,
                    ModelChatClientFactory.isAnthropicModel(model) ? "SYSTEM_ONLY" : "off");
            log.debug("System prompt:\n{}", SYSTEM_PROMPT);
            log.debug("User prompt completo:\n{}", userPrompt);

            // Gemini: system prompt merged nel user message per garantire che le istruzioni
            // vengano applicate correttamente (Spring AI Vertex AI gestisce system() in modo
            // meno vincolante rispetto ad Anthropic).
            // Nota: il limite massimo dei modelli Gemini è 65535 (bound esclusivo), non 65536.
            String effectiveSystem = SYSTEM_PROMPT;
            String effectiveUser = userPrompt;
            int maxOutputTokens = 16000;
            if (ModelChatClientFactory.isGeminiModel(model)) {
                effectiveUser = "[ISTRUZIONI OBBLIGATORIE - APPLICARE CON PRIORITÀ ASSOLUTA]\n"
                        + SYSTEM_PROMPT.strip()
                        + "\n\n"
                        + userPrompt;
                effectiveSystem = "Sei un assistente AI specializzato in analisi EPC oil & gas. Segui le istruzioni nel messaggio utente.";
                maxOutputTokens = 65535;
            }

            int estimatedTokens = effectiveUser.length() / 3;
            if (ModelChatClientFactory.isAnthropicModel(model)) {
                tokenRateLimiter.waitIfNeeded(estimatedTokens);
            }

            ChatResponse response = callModelWithCancellationCheck(jobId, model, effectiveSystem, effectiveUser, maxOutputTokens, pageImages);

            int actualTokens = extractActualTokens(response, estimatedTokens);
            if (ModelChatClientFactory.isAnthropicModel(model)) {
                tokenRateLimiter.recordUsage(actualTokens);
            }

            String reportJson = response.getResult().getOutput().getText();
            log.info("Risposta modello ricevuta: {} caratteri | token totali: {} | modello={}",
                    reportJson != null ? reportJson.length() : 0, actualTokens, model);
            log.debug("JSON preventivo completo:\n{}", reportJson);

            // Step 6 – Generazione documento Word
            updateProgress(jobId, 85, "Generazione documento Word");
            byte[] docx = estimateReportBuilder.build(reportJson, info, originalFilename, searchResults);

            // Step 7 – Salvataggio risultato
            Estimate job = loadJob(jobId);
            String fileName = job.getResultFileName() != null
                    ? job.getResultFileName()
                    : "estimate-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".docx";

            job.setStatus(Estimate.JobStatus.COMPLETED);
            job.setProgress(100);
            job.setCurrentStep("Preventivo completato");
            job.setResultFileName(fileName);
            job.setResultFileContent(docx);
            estimateRepository.save(job);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("END generazione preventivo | jobId={} | model={} | tempo={}s", jobId, model, elapsed / 1000);

        } catch (JobCancelledException e) {
            log.info("Job annullato dall'utente | jobId={}", jobId);
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
            Map<String, List<WebSearchService.SearchResult>> searchResults,
            boolean hasImages,
            double usdPerEurRate) throws Exception {

        // Map.of() supporta max 10 entries — usiamo LinkedHashMap per mantenere l'ordine
        Map<String, Object> projectInfoMap = new LinkedHashMap<>();
        projectInfoMap.put("nazione", info.nazione() != null ? info.nazione() : "N/D");
        projectInfoMap.put("tipo_progetto", info.tipoProgetto() != null ? info.tipoProgetto() : "N/D");
        projectInfoMap.put("diametro_pollici", info.diametroPollici() != null ? info.diametroPollici() : "N/D");
        projectInfoMap.put("lunghezza_km", info.lunghezzaKm() != null ? info.lunghezzaKm() : "N/D");
        projectInfoMap.put("durata_mesi", info.durataMesi() != null ? info.durataMesi() : "N/D");
        projectInfoMap.put("num_spread", info.numSpread() != null ? info.numSpread() : "N/D");
        projectInfoMap.put("avanzamento_m_giorno", info.avanzamentoMGiorno() != null ? info.avanzamentoMGiorno() : "N/D");
        projectInfoMap.put("scope_lavori", info.scopeLavori() != null ? info.scopeLavori() : "N/D");
        projectInfoMap.put("zona_geografica", info.zonaGeografica() != null ? info.zonaGeografica() : "N/D");
        projectInfoMap.put("pressione_progetto_bara", info.pressioneProgettoBara() != null ? info.pressioneProgettoBara() : "N/D");
        projectInfoMap.put("note_tecniche", info.noteTecniche() != null ? info.noteTecniche() : "N/D");
        String projectInfoJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(projectInfoMap);

        String pricingSection = (internalPricing == null || internalPricing.isBlank())
                ? "Nessun dato interno disponibile. Usa esclusivamente dati di mercato."
                : internalPricing;
        String rateText = formatRate(usdPerEurRate);

        StringBuilder sb = new StringBuilder();
        sb.append("[INFORMAZIONI PROGETTO ESTRATTE DAL DOCUMENTO]\n");
        sb.append(projectInfoJson).append("\n\n");

        sb.append("[PREZZI INTERNI AZIENDALI - DA UTILIZZARE CON PRIORITÀ MASSIMA]\n");
        sb.append("I dati sono ordinati dal più recente al più vecchio. In caso di valori contrastanti per la stessa voce di costo, il documento con data di caricamento più recente prevale.\n");
        sb.append(pricingSection).append("\n\n");

        sb.append("[CAMBIO EUR/USD APPLICATO]\n");
        sb.append("1 EUR = ").append(rateText).append(" USD. Usa questo valore esatto in cambio_eur_usd e per convertire qualsiasi benchmark espresso in USD. Il JSON finale deve restare in EUR.\n\n");

        sb.append("[DATI DI MERCATO AGGIORNATI - NAZIONE: ")
                .append(info.nazione() != null ? info.nazione() : "N/D").append("]\n");

        for (Map.Entry<String, List<WebSearchService.SearchResult>> entry : searchResults.entrySet()) {
            sb.append("=== ").append(entry.getKey()).append(" ===\n");
            List<WebSearchService.SearchResult> results = entry.getValue();
            if (results == null || results.isEmpty()) {
                sb.append("Nessun dato disponibile.\n\n");
            } else {
                for (int j = 0; j < results.size(); j++) {
                    WebSearchService.SearchResult r = results.get(j);
                    sb.append("[").append(j + 1).append("] ").append(r.title()).append("\n");
                    sb.append(r.url()).append("\n");
                    String content = r.content() != null ? r.content() : "";
                    sb.append(truncate(content, TAVILY_CONTENT_MAX_CHARS)).append("\n\n");
                }
            }
        }

        sb.append("[ISTRUZIONI]\n");
        if (hasImages) {
            sb.append("Sono allegate tavole visuali composite del documento PDF.\n");
            sb.append("Ogni tavola puo contenere piu figure o pagine rappresentative: analizza attentamente eventuali Gantt, cronoprogrammi, schemi tecnici o tabelle.\n");
            sb.append("Usa le informazioni visive per ricavare durate delle fasi, sequenze di attività e dati tecnici non presenti nel testo.\n");
        }
        sb.append("Genera un preventivo dettagliato per questo progetto.\n");

        String tipoUp = info.tipoProgetto() != null ? info.tipoProgetto().toUpperCase() : "";

        sb.append("Voce VI.a Overhead (OH): 6% del subtotale I-V. Voce VI.b Contingency: 2% del subtotale I-V. Voce VI.c Costi Finanziari e Assicurazioni: 3-5% del subtotale I-V.\n");
        sb.append("Includi sempre: mobilizzazione, costruzione, subcontratti (Forniture + Subappalti separati), indiretti, vitto/alloggio, OH/contingency/finanziari.\n");
        sb.append("Tutti gli importi in EUR. Usa il cambio EUR/USD riportato nella sezione [CAMBIO EUR/USD APPLICATO] per cambio_eur_usd e per convertire qualsiasi benchmark in USD trovato nelle ricerche di mercato.\n");
        sb.append("Il report deve essere dettagliato e professionale.\n\n");

        sb.append("Restituisci ESCLUSIVAMENTE questo JSON (tutti gli importi sono in EUR; le chiavi che terminano in '_usd' contengono comunque valori in EUR per compatibilità con il vecchio schema):\n");
        sb.append("""
                {
                  "nazione": "...",
                  "tipo_progetto": "...",
                  "cambio_eur_usd": __CAMBIO_EUR_USD__,
                  "estimate_status": "ESTIMATE_DEFINITIVE | ESTIMATE_WITH_ASSUMPTIONS",
                  "assumptions_taken": [
                    "Assunzione progettuale 1 con razionale",
                    "Assunzione progettuale 2 con razionale"
                  ],
                  "executive_summary": "max 3 frasi per top management: configurazione progetto, prezzo finale, EUR/km, posizionamento benchmark",
                  "scope_check": [
                    {"codice": "A", "voce": "Engineering",                  "stato": "INCLUSO|PARZIALE|ESCLUSO|INCERTO", "note": "..."},
                    {"codice": "B", "voce": "Procurement",                  "stato": "...", "note": "..."},
                    {"codice": "C", "voce": "Costruzione pipeline meccanica","stato": "...", "note": "..."},
                    {"codice": "D", "voce": "Costruzione pipeline civile",  "stato": "...", "note": "..."},
                    {"codice": "E", "voce": "Attraversamenti speciali",     "stato": "...", "note": "..."},
                    {"codice": "F", "voce": "Installazione E&I",            "stato": "...", "note": "..."},
                    {"codice": "G", "voce": "Stazioni BVS/Scraper",         "stato": "...", "note": "..."},
                    {"codice": "H", "voce": "Stazioni Compression/Metering","stato": "...", "note": "..."}
                  ],
                  "quadro_economico": [
                    {"voce": "I - Mobilizzazione e Temporary Facilities", "importo_usd": 0, "percentuale": 0.0, "note": "..."},
                    {"voce": "II - Costruzione (mezzi + personale + carburante)", "importo_usd": 0, "percentuale": 0.0, "note": "..."},
                    {"voce": "III - Subcontratti e Forniture", "importo_usd": 0, "percentuale": 0.0, "note": "..."},
                    {"voce": "IV - Indiretti", "importo_usd": 0, "percentuale": 0.0, "note": "..."},
                    {"voce": "V - Vitto e Alloggio", "importo_usd": 0, "percentuale": 0.0, "note": "..."},
                    {"voce": "SUBTOTALE I-V", "importo_usd": 0, "percentuale": 100.0, "note": ""},
                    {"voce": "VI.a - Overhead / OH (6%)", "importo_usd": 0, "percentuale": 6.0, "note": "6% su subtotale I-V"},
                    {"voce": "VI.b - Contingency (2%)", "importo_usd": 0, "percentuale": 2.0, "note": "2% su subtotale I-V"},
                    {"voce": "VI.c - Costi Finanziari e Assicurazioni (4%)", "importo_usd": 0, "percentuale": 4.0, "note": "3-5% su subtotale I-V"},
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
                  "benchmark_comparison": {
                    "zona_riferimento": "es. Pianura agricola 42-48\\"",
                    "eur_km_progetto": 0,
                    "eur_km_benchmark_min": 0,
                    "eur_km_benchmark_max": 0,
                    "eur_km_posizionamento": "BASSO|MEDIO|ALTO|FUORI RANGE",
                    "eur_inch_metro_progetto": 0,
                    "eur_inch_metro_benchmark_min": 0,
                    "eur_inch_metro_benchmark_max": 0,
                    "eur_inch_metro_posizionamento": "BASSO|MEDIO|ALTO|FUORI RANGE",
                    "commento": "1-2 frasi che spiegano il posizionamento rispetto al benchmark"
                  },
                  "analisi_dettaglio": [
                    {
                      "categoria": "Mobilizzazione e Temporary Facilities",
                      "importo_usd": 0,
                      "descrizione": "...",
                      "produttivita_applicata": null,
                      "voci_principali": [
                        {"descrizione": "...", "quantita": "...", "costo_unitario_usd": "...", "fonte_dato": "AZIENDALE|BENCHMARK|ASSUNZIONE", "totale_usd": 0}
                      ],
                      "assunzioni": ["..."],
                      "rischi": ["..."]
                    },
                    {
                      "categoria": "III.a - Forniture materiali progetto",
                      "importo_usd": 0,
                      "descrizione": "...",
                      "voci_principali": [],
                      "assunzioni": [],
                      "rischi": []
                    },
                    {
                      "categoria": "III.b - Subappalti",
                      "importo_usd": 0,
                      "descrizione": "...",
                      "voci_principali": [],
                      "assunzioni": [],
                      "rischi": []
                    }
                  ],
                  "rischi_principali": [
                    {"categoria": "...", "descrizione": "...", "impatto": "ALTO|MEDIO|BASSO", "impatto_eur": 0, "mitigazione": "..."}
                  ],
                  "sensitivity_analysis": [
                    {"scenario": "Base case",          "descrizione": "Caso base secondo ipotesi attuali", "variazione_percentuale": 0.0,  "prezzo_eur": 0, "delta_eur": 0},
                    {"scenario": "Pessimistico -15%",  "descrizione": "...",                                "variazione_percentuale": -15.0,"prezzo_eur": 0, "delta_eur": 0},
                    {"scenario": "Ottimistico +10%",   "descrizione": "...",                                "variazione_percentuale": 10.0, "prezzo_eur": 0, "delta_eur": 0},
                    {"scenario": "Cambio EUR/USD ±10%","descrizione": "...",                                "variazione_percentuale": 10.0, "prezzo_eur": 0, "delta_eur": 0},
                    {"scenario": "Rese -10%",          "descrizione": "...",                                "variazione_percentuale": -10.0,"prezzo_eur": 0, "delta_eur": 0}
                  ],
                  "raccomandazioni_contrattuali": [
                    {"tema": "Clausole | Penali | Garanzie | Risk Allocation", "raccomandazione": "...", "motivazione": "..."}
                  ],
                  "cronoprogramma_sintetico": [
                    {"fase": "...", "durata": "...", "settimane": "...", "note": "..."}
                  ],
                  "note_finali": "..."
                }
                """.replace("__CAMBIO_EUR_USD__", rateText));

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    // Timeout massimo per la chiamata al modello: 12 minuti.
    // Se il modello non risponde entro questo limite il job viene marcato FAILED.
    private static final int MODEL_CALL_TIMEOUT_MINUTES = 12;

    /**
     * Esegue la chiamata al modello in un thread separato e controlla ogni 5 secondi
     * se il job è stato annullato dall'utente o se è scaduto il timeout.
     * In entrambi i casi il Future viene cancellato (con interrupt) e il thread
     * dell'executor viene liberato per i job successivi.
     */
    private ChatResponse callModelWithCancellationCheck(UUID jobId, String model,
            String systemPrompt, String userPrompt, int maxTokens, List<byte[]> images) {

        boolean useCache = ModelChatClientFactory.isAnthropicModel(model);
        CompletableFuture<ChatResponse> future = CompletableFuture.supplyAsync(
                () -> modelFactory.callWithImages(model, systemPrompt, userPrompt, maxTokens, useCache, images)
        );

        long deadlineMs = System.currentTimeMillis() + MODEL_CALL_TIMEOUT_MINUTES * 60_000L;

        while (!future.isDone()) {
            try {
                throwIfCancelled(jobId);
            } catch (JobCancelledException e) {
                future.cancel(true);
                throw e;
            }

            if (System.currentTimeMillis() > deadlineMs) {
                future.cancel(true);
                throw new IllegalStateException(
                        "Timeout: il modello non ha risposto entro " + MODEL_CALL_TIMEOUT_MINUTES + " minuti");
            }

            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new JobCancelledException(jobId);
            }
        }

        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JobCancelledException(jobId);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }

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

    private void throwIfCancelled(UUID jobId) {
        if (loadJob(jobId).getStatus() == Estimate.JobStatus.CANCELLED) {
            throw new JobCancelledException(jobId);
        }
    }

    private Estimate loadJob(UUID jobId) {
        return estimateRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("Job non trovato: " + jobId));
    }

    private String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 3) + "...";
    }

    private static String formatRate(double rate) {
        return String.format(Locale.US, "%.4f", rate);
    }

    private int extractActualTokens(ChatResponse response, int fallback) {
        try {
            if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                return response.getMetadata().getUsage().getTotalTokens();
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private String formatResultsForLog(List<WebSearchService.SearchResult> results) {
        if (results == null || results.isEmpty()) return "(nessun risultato)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            WebSearchService.SearchResult r = results.get(i);
            String snippet = r.content() != null
                    ? r.content().substring(0, Math.min(150, r.content().length())) + "..."
                    : "";
            sb.append("  [").append(i + 1).append("] ").append(r.title()).append("\n");
            sb.append("       ").append(r.url()).append("\n");
            sb.append("       ").append(snippet).append("\n");
        }
        return sb.toString();
    }
}
