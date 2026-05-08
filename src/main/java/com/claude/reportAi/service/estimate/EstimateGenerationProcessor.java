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
import org.springframework.beans.factory.annotation.Value;
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
    private final ExclusionsExtractor exclusionsExtractor;
    private final InternalPricingRetriever internalPricingRetriever;
    private final WebSearchService webSearchService;
    private final ModelChatClientFactory modelFactory;
    private final TokenRateLimiter tokenRateLimiter;
    private final EstimateReportBuilder estimateReportBuilder;
    private final PdfPageImageExtractor pdfPageImageExtractor;
    private final ObjectMapper objectMapper;
    private final ExchangeRateService exchangeRateService;

    @Value("${app.estimate.extractor-model.anthropic:claude-haiku-4-5-20251001}")
    private String extractorModelAnthropic;

    @Value("${app.estimate.extractor-model.gemini:gemini-2.0-flash}")
    private String extractorModelGemini;

    private static final int TAVILY_CONTENT_MAX_CHARS = 400;

    private static final String SYSTEM_PROMPT = """
            <role>
            You are a senior cost estimator and tendering manager with 30 years of experience in EPC oil & gas onshore projects (pipelines and plants).
            INPUT: Scope of Work + Schedule + technical documents.
            OUTPUT: Valid JSON (schema in user message). Language: Italian. Currency: EUR.
            Use the EUR/USD rate provided in the user message for cambio_eur_usd and to convert USD benchmarks; no USD values in the final JSON.
            </role>

            <steps>

            <step id="1" name="SCOPE CHECK">
            For each of the 9 items A-I, assign status {INCLUSO|PARZIALE|ESCLUSO|INCERTO}:
            A. ENGINEERING (basic/FEED/detail/iso/P&ID)
            B. PROCUREMENT (line pipe, valvole, equipment, compressori, skid, bulk, cavi, strutture)
            C. COSTRUZIONE PIPELINE MECCANICA (linea+tie-in+collaudo+FJC)
            D. COSTRUZIONE PIPELINE CIVILE (scavo+rinterro+ripristini) — if ESCLUSO: add standby risk in rischi_principali with impatto_eur in EUR/day
            E. ATTRAVERSAMENTI SPECIALI (HDD/TOC/microtunnel/spingitubo/cielo aperto)
            F. INSTALLAZIONE E&I (cabling, FOC, PC, SCADA)
            G. STAZIONI BVS/SCRAPER (size by diameter/m²/m³ concrete/weight)
            H. STAZIONI COMPRESSION/METERING (size by diameter/m²/m³ concrete/weight)
            I. CAMP/FACILITIES PER IL PERSONALE (verificare se strutture esistenti —hotel, compound, uffici— possono accomodare il personale o se è necessario costruire un campo/ufficio base)
            Never ask for clarifications. If INCERTO: assume the most reasonable hypothesis, populate assumptions_taken, set estimate_status="ESTIMATE_WITH_ASSUMPTIONS".
            </step>

            <step id="2" name="SOURCE HIERARCHY (binding)">
            Priority: (1) company files → (2) company historical data → (3) market benchmarks → (4) standard assumptions.
            Company files ALWAYS prevail over benchmarks, for both costs and productivity rates:
            - Never replace an internal price with a market price
            - Never replace a company productivity rate with a benchmark rate
            - Partially covered items: use company data as base, integrate missing part with market data
            - If company files contain rates by diameter/terrain/technique, use the most relevant, not a generic average
            - For each benchmark item, document in assumptions: which benchmark, range, why applicable
            </step>

            <step id="3" name="CALCULATION RULES">
            • Equipment depreciation: pro-rata on weeks of ACTUAL use, not total project duration
            • Fuel utilization factor: 55-65% if spread <700 m/day | 75-80% if >700 m/day | 50-60% yard equipment
            • Sideboom+paywelder sized for mainline + tie-in in parallel. Ref 48": 1 tie-in weld/day = 1 paywelder + 1 excavator + 2 sidebooms. Smaller diameters: scale proportionally. Without tie-in: size only for mainline
            • Direct costs WITHOUT contingency/margins (buffer only in item VI)
            • Quantities only if documented. Without data: HDD/TOC 1 every 15-25 km, casing 3 every 15 km (~30 m each). Microtunnel ONLY if explicit with concrete segments. Without basis, do not include the item
            • Duration = length / (n_spread × productivity × working days/month). Never inflate
            • analisi_dettaglio: ALWAYS two separate categories "III.a - Forniture materiali progetto" and "III.b - Subappalti"
            • Stations (BVS, SS, LS, compression): first company data, then size by actual diameter/m²/m³/weight without overestimation
            </step>

            <step id="4" name="BENCHMARKS (EUR; convert USD using rate from user message)">
            EPC TOTAL EUR/km by zone × diameter (42-48" | 24-36" | 8-20"):
            Flat simple       1.5-3.2M | 1.2-2.6M | 0.8-1.5M
            Agricultural flat 1.8-3.5M | +15%     | +10%
            Hilly             2.8-4.2M | 1.9-3.2M | 1.2-2.2M
            Mountain EU       3.5-5.5M
            Extreme mountain  5.2-7.2M | 3.1-5.5M | 1.8-3.0M
            Arctic            6.4-9.6M
            Jungle/swamp      5.6-8.0M
            EUR/inch-metre 42-48": flat 40-60 | hilly 60-95 | mountain 84-148

            LINE PIPE X70 EXW (CIF EU 2025-26): scaling 1.2-1.5 EUR/kg.
            48"WT22 580-730 EUR/m | 42"WT20 480-620 | 36"WT17 380-520 | 24"WT12 200-280 | 16"WT9 130-190

            BALL VALVES CL600 with actuator (k EUR/unit):
            48" 480-720 | 36" 320-480 | 24" 160-250 | 16" 90-150

            COMPRESSOR 20-30 MW: 25-45 M EUR (~1 M EUR/MW compressor package; add central accessories)

            CROSSINGS EUR/m (ref 48", scale for smaller diameters; company prices take precedence):
            Casing flat 800-1,000 | HDD flat 2,500-5,500 | HDD mountain 5,000-9,000 | Microtunnel concrete 10,000-15,000

            NDT (100% RX+AUT H2-ready): tie-in/manual 100-150 EUR/joint | mainline ~2,000 EUR/day/spread

            CATHODIC PROTECTION: 20-35 kEUR/km | FOC+HDPE: 40-65 kEUR/km
            DEG (detail engineering): max 2% total or ~75 EUR/estimated hour
            BASE CAMP 300 persons: 1-3 M EUR (if no hotel; company data takes precedence)
            CATERING: rural workers EU 40-55 EUR/p/day | staff hotel 60-90 EUR/p/day | 26 days/month
            TOTAL V (catering/accommodation): 2-4% total cost

            STANDBY: full spread 190-250 kEUR/day | partial (welding) 100-120 | camp+indirects 35-55

            SECURITY (% total cost):
            Low (EU/NA) 0.5-1% | Medium (stable LATAM, SE Asia) 1.5-3% | High (N Mexico, Sub-Saharan Africa, ME) 3-6% | Extreme (conflict) 6-12% + K&R + PV
            </step>

            <step id="5" name="COST BREAKDOWN STRUCTURE">
            I. Mob+Temp Facilities | II. Construction (equipment+personnel+fuel)
            III. Subcontracts+Supplies (split III.a Supplies | III.b Subcontracts)
            IV. Indirects | V. Catering/Accommodation
            SUBTOTAL I-V
            VI. on subtotal I-V: VI.a OH 6% + VI.b Contingency 2% + VI.c Insurance/Financial 3-5% (typical total 12%)
            VII. TOTAL COST = (I-V) + VI
            VIII. PRICE = VII × (1 + margin 6-10%, typical 8%)
            </step>

            <step id="6" name="NARRATIVE REPORT">
            - executive_summary: max 3 sentences for top management (configuration, price, EUR/km, benchmark positioning)
            - For each item: document fonte_dato (AZIENDALE/BENCHMARK/ASSUNZIONE), productivity rates, quantities, calculation logic
            - rischi_principali: prioritized with ALTO/MEDIO/BASSO level and quantified impatto_eur in EUR
            - cronoprogramma_sintetico: max 8 fasi, note max 15 parole per fase. MANDATORY: populate always, never emit empty array
            - sensitivity_analysis: ≥5 scenarios (base, pessimistic -15%, optimistic +10%, FX ±10%, productivity -10%)
            - raccomandazioni_contrattuali: min 3, max 5 items — tema (max 5 words), raccomandazione (max 2 sentences), motivazione (max 1 sentence). MANDATORY: complete all items before emitting note_finali
            </step>

            <step id="7" name="ANTI-OVERESTIMATION AND CONSISTENCY CHECK (execute before emitting)">
            SCOPE: scope_check on all 9 items A-I; if civil=ESCLUSO add standby risk in EUR/day; if engineering=INCLUSO add DEG in III cap 2%; if procurement=ESCLUSO no line pipe/valves in III; if crossings=only mechanical, no HDD/TOC/microtunnel.
            NUMERICAL: Analytical sum I-V = subtotal ±2%; final EUR/km within benchmark zone ±25%; EUR/inch-m within benchmark ±25%; distribution I-V (Mob 3-5% | Construction 30-40% | Supplies 40-55% | Indirects 6-10% | Catering 4-7%); contingency ONLY ONCE; margin on post-contingency cost; n_spread × duration × productivity = length ±5%; total duration ≤ Gantt; no "Miscellaneous" >3% per chapter.
            ANTI-OVERESTIMATION: company prices/rates always prevail over benchmarks; no double markups (buffer only in VI); no implicit safety coefficients in productivity rates; quantities only if documented; monthly spread cap not above benchmark unless justified by company data; line pipe EUR/m consistent with 1.2-1.5 EUR/kg on pipe weight; fuel factor consistent with spread productivity; depreciation = actual weeks of use.
            COMPLETENESS: assumptions_taken populated for every item not from company files; sensitivity ≥5 scenarios including base; recommendations ≥3; all amounts in EUR.
            If any check fails, correct before emitting.
            </step>

            </steps>

            <guidelines>
            - Valid JSON output only, no text before/after, no markdown.
            - Mandatory fields: scope_check (9 items A-I), estimate_status, assumptions_taken, executive_summary, cambio_eur_usd (value from user message), quadro_economico (I-VIII + subtotal; optional percentages), kpi (optional/null derived fields), benchmark_comparison, analisi_dettaglio (including III.a and III.b separately), rischi_principali (with impatto_eur), sensitivity_analysis (≥5), raccomandazioni_contrattuali (≥3), cronoprogramma_sintetico, note_finali.
            - All amounts in EUR.
            - Respond in Italian.
            </guidelines>
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
            String extractorModel = resolveExtractorModel(model);
            ProjectInfoExtractor.ProjectInfo info = projectInfoExtractor.extract(fullText, extractorModel);
            log.info("Info estratte: nazione={}, tipo={}, km={}, mesi={} [extractor={}]",
                    info.nazione(), info.tipoProgetto(), info.lunghezzaKm(), info.durataMesi(), extractorModel);

            // Step 2b – Estrazione esclusioni e battery limits
            throwIfCancelled(jobId);
            updateProgress(jobId, 18, "Analisi esclusioni e battery limits");
            ExclusionsExtractor.ExclusionContext exclusions = exclusionsExtractor.extract(fullText, extractorModel);

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
            String userPrompt = buildUserPrompt(info, exclusions, internalPricing, searchResults, !pageImages.isEmpty(), usdPerEurRate);

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
            int maxOutputTokens = 20000;
            if (ModelChatClientFactory.isGeminiModel(model)) {
                effectiveUser = "<mandatory_instructions priority=\"ABSOLUTE\">\n"
                        + SYSTEM_PROMPT.strip()
                        + "\n</mandatory_instructions>\n\n"
                        + userPrompt;
                effectiveSystem = "You are an AI assistant specialized in EPC oil & gas analysis. Follow the instructions in the user message.";
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
            byte[] docx = estimateReportBuilder.build(reportJson, info, originalFilename);

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
            ExclusionsExtractor.ExclusionContext exclusions,
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
        sb.append("<project_information>\n");
        sb.append(projectInfoJson).append("\n");
        sb.append("</project_information>\n\n");

        sb.append("<internal_pricing priority=\"MAXIMUM\">\n");
        sb.append("<note>Data ordered from most recent to oldest. For conflicting values on the same cost item, the most recently uploaded document takes precedence.</note>\n");
        sb.append(pricingSection).append("\n");
        sb.append("</internal_pricing>\n\n");

        sb.append("<exchange_rate>\n");
        sb.append("1 EUR = ").append(rateText).append(" USD. Use this exact value for cambio_eur_usd and to convert any benchmark expressed in USD. The final JSON must remain in EUR.\n");
        sb.append("</exchange_rate>\n\n");

        sb.append(buildExclusionsSection(exclusions));

        sb.append("<market_data country=\"")
                .append(info.nazione() != null ? info.nazione() : "N/D").append("\">\n");

        for (Map.Entry<String, List<WebSearchService.SearchResult>> entry : searchResults.entrySet()) {
            sb.append("=== ").append(entry.getKey()).append(" ===\n");
            List<WebSearchService.SearchResult> results = entry.getValue();
            if (results == null || results.isEmpty()) {
                sb.append("No data available.\n\n");
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

        sb.append("</market_data>\n\n");

        sb.append("<instructions>\n");
        if (hasImages) {
            sb.append("Attached visual composite tables from the PDF document are included.\n");
            sb.append("Each table may contain multiple representative figures or pages: carefully analyze any Gantt charts, schedules, technical diagrams, or tables.\n");
            sb.append("Use the visual information to extract phase durations, activity sequences, and technical data not present in the text.\n");
        }
        sb.append("Generate a detailed cost estimate for this project.\n");

        String tipoUp = info.tipoProgetto() != null ? info.tipoProgetto().toUpperCase() : "";

        sb.append("Item VI.a Overhead (OH): 6% of subtotal I-V. Item VI.b Contingency: 2% of subtotal I-V. Item VI.c Financial costs and Insurance: 3-5% of subtotal I-V.\n");
        sb.append("Always include: mobilization, construction, subcontracts (Supplies + Subcontracts separately), indirects, catering/accommodation, OH/contingency/financial costs.\n");
        sb.append("All amounts in EUR. Use the EUR/USD rate from the <exchange_rate> section for cambio_eur_usd and to convert any USD benchmark found in market research.\n");
        sb.append("The report must be detailed and professional.\n");
        sb.append("</instructions>\n\n");

        sb.append("Return ONLY the following JSON (all amounts in EUR; keys ending in '_usd' also contain EUR values for backward schema compatibility):\n");
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
                    {"codice": "H", "voce": "Stazioni Compression/Metering","stato": "...", "note": "..."},
                    {"codice": "I", "voce": "Camp/Facilities per il Personale", "stato": "INCLUSO|PARZIALE|ESCLUSO|INCERTO", "note": "..."}
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
                  "raccomandazioni_contrattuali": [
                    {"tema": "Clausole | Penali | Garanzie | Risk Allocation", "raccomandazione": "...", "motivazione": "..."}
                  ],
                  "cronoprogramma_sintetico": [
                    {"fase": "...", "durata": "...", "settimane": "...", "note": "..."}
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
                  "note_finali": "..."
                }
                """.replace("__CAMBIO_EUR_USD__", rateText));

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds the XML exclusions block injected into the user prompt.
     * When exclusions are present, they act as hard constraints: the model must not include
     * these items as base costs in any cost breakdown category (I through V).
     */
    private String buildExclusionsSection(ExclusionsExtractor.ExclusionContext exclusions) {
        StringBuilder sb = new StringBuilder();
        sb.append("<exclusions_and_battery_limits>\n");

        if (!exclusions.hasAnyExclusion()) {
            sb.append("<note>No explicit exclusions were identified in the document. Apply standard assumptions.</note>\n");
            sb.append("</exclusions_and_battery_limits>\n\n");
            return sb.toString();
        }

        sb.append("<rule>The following items are EXPLICITLY EXCLUDED from the base Contractor scope. ")
          .append("Do NOT include them as base costs under any category (I through V). ")
          .append("If relevant, list them as risks or optional items only.</rule>\n");

        if (exclusions.fuelByClient()) {
            sb.append("<excluded_item>Fuel for construction and transport equipment — furnished by COMPANY/Client</excluded_item>\n");
        }
        if (exclusions.securityExcluded()) {
            sb.append("<excluded_item>Security, guarding, and escort services — excluded from Contractor scope</excluded_item>\n");
        }
        if (exclusions.hddMajorExcluded()) {
            sb.append("<excluded_item>Major HDD/horizontal directional drilling crossings — excluded or to be quoted separately</excluded_item>\n");
        }
        if (exclusions.chemicalInjectionExcluded()) {
            sb.append("<excluded_item>Chemical injection package — excluded from Contractor scope</excluded_item>\n");
        }
        if (exclusions.permitsExcluded()) {
            sb.append("<excluded_item>Permits, authorizations, and right-of-way — under Client/End User responsibility</excluded_item>\n");
        }
        if (exclusions.satFatExcluded()) {
            sb.append("<excluded_item>SAT/FAT client attendance, operator training, and 2-year spare parts — excluded</excluded_item>\n");
        }
        for (String item : exclusions.otherExclusions()) {
            sb.append("<excluded_item>").append(item).append("</excluded_item>\n");
        }

        if (!exclusions.clientSuppliedItems().isEmpty()) {
            sb.append("<client_supplied>\n");
            for (String item : exclusions.clientSuppliedItems()) {
                sb.append("  <item>").append(item).append("</item>\n");
            }
            sb.append("</client_supplied>\n");
        }

        if (!exclusions.itemsToQuoteSeparately().isEmpty()) {
            sb.append("<quote_separately>\n");
            for (String item : exclusions.itemsToQuoteSeparately()) {
                sb.append("  <item>").append(item).append("</item>\n");
            }
            sb.append("</quote_separately>\n");
        }

        if (!exclusions.batteryLimits().isEmpty()) {
            sb.append("<battery_limits>\n");
            for (String item : exclusions.batteryLimits()) {
                sb.append("  <item>").append(item).append("</item>\n");
            }
            sb.append("</battery_limits>\n");
        }

        sb.append("</exclusions_and_battery_limits>\n\n");
        return sb.toString();
    }

    // Uses a cheap/fast model for extraction tasks (ProjectInfo, Exclusions) regardless of the main
    // model selected by the user — avoids consuming Anthropic TPM capacity before the main call.
    private String resolveExtractorModel(String mainModel) {
        if (ModelChatClientFactory.isAnthropicModel(mainModel)) {
            return extractorModelAnthropic;
        }
        return extractorModelGemini;
    }

    // Timeout massimo per la chiamata al modello principale.
    // Aumentato a 20 min per Opus (step di estrazione ora usano modelli veloci separati).
    private static final int MODEL_CALL_TIMEOUT_MINUTES = 20;

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
