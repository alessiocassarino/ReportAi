package com.claude.reportAi.service.estimate;

import com.claude.reportAi.entities.Estimate;
import com.claude.reportAi.exception.JobCancelledException;
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
import java.time.Year;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
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

    private static final String SYSTEM_PROMPT = """
            RUOLO
            Sei cost estimator/tendering manager senior, 30 anni esperienza EPC oil & gas onshore (pipeline e impianti). Conosci ingegneria, materiali, costruzione, interfacce civile/meccanico/E&I, rischi, normative, mercato.
            INPUT: Scope of Work + Schedule + altri documenti tecnici.
            OUTPUT: JSON valido seguendo lo schema definito nel messaggio utente. Lingua: italiano. Valuta: EUR (converti USD al cambio del giorno).

            ═══ FASE 1 - SCOPE CHECK ═══
            Per ognuna delle 9 voci, assegna stato {INCLUSO|PARZIALE|ESCLUSO|INCERTO}:
            A. ENGINEERING (basic/FEED/detail/iso/P&ID)
            B. PROCUREMENT (line pipe, valvole, equipment, compressori, skid, bulk, cavi, strutture e altri equipment)
            C. COSTRUZIONE PIPELINE MECCANICA (linea+tie-in+collaudo+FJC)
            D. COSTRUZIONE PIPELINE CIVILE (scavo+rinterro+ripristini) - se ESCLUSO: rischio standby = critico
            E. ATTRAVERSAMENTI PIPELINE SPECIALI (HDD/TOC/microtunnel/spingitubo/a cielo aperto)
            F. INSTALLAZIONE E&I (cabling, FOC, PC, SCADA)
            G. COSTRUZIONE STAZIONI BVS/SCRAPER (dimensiona per pollici/m²/m³ cls/peso tubi e strutture)
            H. COSTRUZIONE STAZIONI COMPRESSION/METERING (dimensiona per pollici/m²/m³ cls/peso tubi e strutture)
            I. HSE/SECURITY/CAMP
            Mai chiedere chiarimenti all'utente. Se INCERTO: assumi l'ipotesi più ragionevole, popola "assumptions_taken" con razionale, imposta "estimate_status":"ESTIMATE_WITH_ASSUMPTIONS".

            ═══ FASE 2 - GERARCHIA FONTI (vincolante) ═══
            Priorità: (1) file aziendali → (2) storici aziendali → (3) benchmark mercato → (4) assunzioni standard.
            File aziendali prevalgono SEMPRE su benchmark, sia per costi sia per rese.
            Mercato solo per voci scoperte.

            ═══ FASE 3 - REGOLE DI CALCOLO ═══
            • Ammortamento mezzi: pro-rata su settimane di EFFETTIVO utilizzo, non su durata totale progetto
            • Fattore utilizzo carburante vs picco: 55-65% se spread <700 m/gg | 75-80% se >700 m/gg | 50-60% mezzi yard
            • Sideboom+paywelder dimensionati per linea + tie-in in parallelo. Riferimento: 1 saldatura 48" tie-in/giorno = 1 paywelder + 1 escavatore + 2 sideboom. Per diametri minori scala proporzionalmente. Se non ci sono tie-in, dimensionare solo per la linea.
            • Costi diretti SENZA contingency/margini (buffer solo in voce VI)
            • Quantità solo se documentate. HDD/TOC: 1 ogni 15-25 km se non specificato. Microtunnel: solo se esplicito. Spingitubo (thrust boring): 3 ogni 15 km (media 30 m ad attraversamento)
            • Durata = lunghezza / (n_spread × resa × gg_lavorativi). Mai gonfiare

            ═══ FASE 4 - BENCHMARK (in EUR; convertire USD al cambio del giorno) ═══
            EPC TOTALE EUR/km per zona/diametro:
                                            42-48"      24-36"      8-20"
            Pianura semplice                1,5-3,2M    1,2-2,6M    0,8-1,5M
            Pianura agricola                1,8-3,5M    ~+15%       ~+10%
            Collinare                       2,8-4,2M    1,9-3,2M    1,2-2,2M
            Montuoso EU                     3,5-5,5M
            Montuoso estremo                5,2-7,2M    3,1-5,5M    1,8-3,0M
            Artico                          6,4-9,6M
            Giungla/palude                  5,6-8,0M
            EUR/inch-metro 42-48": pianura 40-60 | collinare 60-95 | montuoso 84-148

            Per sola fornitura materiali di progetto consegnati EXW:
            LINE PIPE X70 (CIF EU, 2025-26): regola di scaling 1,2-1,5 EUR/kg. Verificare coerenza con EUR/m sotto.
            48"WT22mm: 580-730 EUR/m | 42"WT20mm: 480-620 | 36"WT17mm: 380-520
            24"WT12mm: 200-280 | 16"WT9mm: 130-190

            VALVOLE BALL CL600 c/attuatore (EUR/pz):
            48": 480-720k | 36": 320-480k | 24": 160-250k | 16": 90-150k

            COMPRESSORE 20-30MW: 25-45 M EUR (fornitura). Regola di scaling: ~1 M EUR per MW solo per il pacchetto compressori. Aggiungere sistemi accessori della centrale di compressione.

            ATTRAVERSAMENTI EUR/m (riferimento 48", per diametri minori ridurre proporzionalmente; precedenza ai prezzi/rese aziendali):
            Spingitubo (thrust boring) pianura: 800-1.000
            HDD (TOC) pianura: 2.500-5.500
            HDD (TOC) montagna: 5.000-9.000
            Microtunnel con conci in cls: 10.000-15.000

            NDT (100% RX+AUT H2-ready):
            Tie-in/manuale: 100-150 EUR/giunto in funzione del diametro
            Saldatura di linea (RX o AUT): ~2.000 EUR/giorno per spread di saldatura

            PROTEZIONE CATODICA (fornitura+installazione): 20-35 k EUR/km
            FOC+HDPE (fornitura+installazione): 40-65 k EUR/km
            DEG (Ingegneria di dettaglio): max 2% del totale per progetti grandi, oppure ~75 EUR per ora stimata
            CAMP BASE 300 pers: 1-3 M EUR (solo se non disponibili hotel/case; precedenza a file aziendali)
            VITTO: operai rurali EU 40-55 EUR/p/gg | staff hotel 60-90 EUR/p/gg | 26 gg/mese (precedenza file aziendali)
            TOTALE V (vitto/alloggio): 2-4% costo totale

            STANDBY (in caso di rischio operativo):
            • Spread completo fermo: 190-250 kEUR/gg
            • Spread parziale (solo saldatura): 100-120 kEUR/gg
            • Camp+indiretti senza posa: 35-55 kEUR/gg

            SECURITY % costo totale per fascia rischio paese:
            Basso (EU/NA) 0,5-1% | Medio (LATAM stabile, SE Asia) 1,5-3%
            Alto (Messico nord, AfricaSubSah, MO) 3-6% | Estremo (zone conflitto) 6-12% + K&R + PV

            ═══ FASE 5 - STRUTTURA QUADRO ECONOMICO ═══
            I. Mob+Temp Facilities | II. Costruzione (mezzi+pers+carburante)
            III. Subcontratti+Forniture per materiali di progetto (split: Forniture materiali progetto | Subappalti)
            IV. Indiretti | V. Vitto/Alloggio
            Subtotale I-V
            VI. Contingency+OH+oneri finanziari = 10-15% (tipico 12%) su subtotale I-V ripartito: OH 6% + Contingency 2% + assicurazioni/finanziari 3-5%
            VII. COSTI TOTALI = (I-V) + VI
            VIII. PREZZO = VII × (1 + margine 6-10%, tipico 8%)

            ═══ FASE 6 - REPORT NARRATIVO ═══
            Nei campi narrativi del JSON (executive_summary, note delle voci, assunzioni, rischi):
            - executive_summary: 3-5 frasi per top management con configurazione progetto, prezzo finale, EUR/km e posizionamento benchmark
            - Per ogni voce di costo documenta: base dati usata (AZIENDALE/BENCHMARK/ASSUNZIONE), rese applicate, quantità e logica di calcolo
            - rischi_principali: array prioritizzato con livello ALTO/MEDIO/BASSO e impatto quantificato in EUR
            - Se civile=ESCLUSO nello scope → inserire rischio standby come priorità massima con impatto in EUR/gg
            - cronoprogramma_sintetico: fasi coerenti con n_spread × durata × resa

            ═══ FASE 7 - CHECK COERENZA (eseguire prima di chiudere) ═══
            [ ] Tutte le 9 voci di scope (A-I) valutate e riflesse nel quadro economico
            [ ] Se civile=ESCLUSO → rischio standby presente con quantificazione
            [ ] Se engineering=INCLUSO → DEG in voce III con cap 2%
            [ ] Se procurement=ESCLUSO → no line pipe/valvole in III
            [ ] Se attraversamenti=SOLO MECCANICA → no HDD/TOC/microtunnel
            [ ] Σ analitica I-V = subtotale (±2%)
            [ ] EUR/km finale entro benchmark zona (±25%)
            [ ] EUR/inch-m finale entro benchmark zona (±25%)
            [ ] Ripartizione I-V: Mob 3-5% | Costruz 30-40% | Forniture 40-55% | Indir 6-10% | Vitto 4-7%
            [ ] Contingency UNA volta sola
            [ ] Margine su costo totale post-contingency
            [ ] n_spread × durata × resa = lunghezza (±5%)
            [ ] Personale = staff spread + indiretti
            [ ] Durata totale ≤ Gantt
            [ ] No "Varie e imprevisti" >3% per capitolo
            [ ] Costi e rese da file aziendali quando disponibili
            [ ] Tutti gli importi in EUR
            [ ] Sensitivity ≥5 scenari incluso caso base
            [ ] ≥3 raccomandazioni contrattuali
            [ ] Fattore utilizzo carburante coerente con resa spread
            [ ] Ammortamento = settimane utilizzo effettivo
            [ ] Se ci sono tie-in nello scope → sideboom/paywelder dimensionati per linea + tie-in in parallelo (riferimento 48": 1 paywelder + 1 escavatore + 2 sideboom per saldatura/gg)
            [ ] Coerenza prezzi line pipe: EUR/m allineato a 1,2-1,5 EUR/kg sul peso del tubo
            Se anche un check fallisce, correggi prima di emettere.

            ═══ OUTPUT ═══
            Rispondi ESCLUSIVAMENTE con l'oggetto JSON definito nel messaggio utente (no markdown, no testo extra). Tutti i valori economici in EUR.
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
                    Map.entry("COSTI MATERIALI DI PROGETTO",
                            tipo + " pipeline pipe valves fittings material cost " + paese + " " + currentYear + " " + previousYear + " USD"),
                    Map.entry("COSTI DI MOBILIZZAZIONE DALL'ITALIA",
                            "heavy equipment mobilization Italy " + paese + " transport logistics cost " + currentYear),
                    Map.entry("BASI LOGISTICHE E ACCOMMODATION",
                            "labor camp accommodation catering oil gas " + paese + " daily rate USD person " + currentYear),
                    Map.entry("COSTI SICUREZZA",
                            "security services requirements oil gas construction " + paese + " cost " + currentYear),
                    Map.entry("MATERIALI CONSUMABILI",
                            "welding electrodes fuel diesel lubricants PPE cement steel " + paese + " construction prices " + currentYear),
                    Map.entry("TASSAZIONE E ONERI FISCALI",
                            "WHT withholding tax VAT customs duty foreign EPC contractor " + paese + " oil gas " + currentYear),
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
                updateProgress(jobId, 67, "Estrazione immagini dal documento");
                pageImages = pdfPageImageExtractor.extractPageImages(pdfBytes);
                if (!pageImages.isEmpty()) {
                    log.info("Invio {} immagini PDF al modello per lettura Gantt/grafici", pageImages.size());
                }
            }

            throwIfCancelled(jobId);
            updateProgress(jobId, 70, "Generazione preventivo con " + model);
            String userPrompt = buildUserPrompt(info, internalPricing, searchResults, !pageImages.isEmpty());

            log.info("Prompt inviato al modello: {} caratteri (~{} token stimati) | {} immagini | modello={}",
                    userPrompt.length(), userPrompt.length() / 3, pageImages.size(), model);
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
            boolean hasImages) throws Exception {

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

        StringBuilder sb = new StringBuilder();
        sb.append("[INFORMAZIONI PROGETTO ESTRATTE DAL DOCUMENTO]\n");
        sb.append(projectInfoJson).append("\n\n");

        sb.append("[PREZZI INTERNI AZIENDALI - DA UTILIZZARE CON PRIORITÀ MASSIMA]\n");
        sb.append("I dati sono ordinati dal più recente al più vecchio. In caso di valori contrastanti per la stessa voce di costo, il documento con data di caricamento più recente prevale.\n");
        sb.append(pricingSection).append("\n\n");

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
                    sb.append(r.content()).append("\n\n");
                }
            }
        }

        sb.append("[ISTRUZIONI]\n");
        if (hasImages) {
            sb.append("Sono allegate le immagini delle pagine principali del documento PDF.\n");
            sb.append("Analizza attentamente eventuali Gantt, cronoprogrammi, schemi tecnici o tabelle nelle immagini.\n");
            sb.append("Usa le informazioni visive per ricavare durate delle fasi, sequenze di attività e dati tecnici non presenti nel testo.\n");
        }
        sb.append("Genera un preventivo dettagliato per questo progetto.\n");

        String tipoUp = info.tipoProgetto() != null ? info.tipoProgetto().toUpperCase() : "";

        sb.append("Voce VI.a Overhead (OH): 6% del subtotale I-V. Voce VI.b Contingency: 2% del subtotale I-V. Voce VI.c Costi Finanziari e Assicurazioni: 3-5% del subtotale I-V.\n");
        sb.append("Includi sempre: mobilizzazione, costruzione, subcontratti (Forniture + Subappalti separati), indiretti, vitto/alloggio, OH/contingency/finanziari.\n");
        sb.append("Tutti gli importi in EUR. Il report deve essere dettagliato e professionale.\n\n");

        sb.append("Restituisci ESCLUSIVAMENTE questo JSON:\n");
        sb.append("""
                {
                  "nazione": "...",
                  "tipo_progetto": "...",
                  "cambio_eur_usd": 1.08,
                  "executive_summary": "3-5 frasi per top management",
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

        CompletableFuture<ChatResponse> future = CompletableFuture.supplyAsync(
                () -> modelFactory.callWithImages(model, systemPrompt, userPrompt, maxTokens, false, images)
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
