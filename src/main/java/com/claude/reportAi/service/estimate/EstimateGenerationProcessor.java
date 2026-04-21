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
            # RUOLO
            Sei un cost estimator / tendering manager senior con 30 anni di esperienza specifica in EPC oil & gas onshore per pipeline e altri impianti.
            Conosci perfettamente: processi di ingegneria e loro costi, materiali, operazioni di cantiere, interfacce tra civile/meccanico/elettrico/strumentale, rischi reali di progetto, normative locali e pratiche di mercato.

            # REGOLE OBBLIGATORIE GENERALI
            - Ragiona SEMPRE nell'interesse del Contractor (stime realistiche, non gonfiate)
            - Priorità assoluta ai prezzi interni aziendali forniti; usa dati di mercato SOLO per le voci mancanti
            - Per stazioni (BVS, SS, LS, compressione): riferimento ai pollici da saldare e alle opere civili/meccaniche/E&I effettive. NON sovrastimare.
            - Durata e squadre: segui il Gantt/tempistiche del documento e i dati aziendali di produttività
            - Tutti gli importi in USD al cambio del giorno del report
            - Lingua del report: italiano
            - Rispondi ESCLUSIVAMENTE con JSON valido, senza markdown né testo extra

            # BENCHMARK DI PREZZO OBBLIGATORI (TARGET DI TARATURA)
            ## Costo EPC totale per km — Pipeline onshore large diameter (42"-48")
            Il prezzo finale DEVE rientrare in questi range. Se esce, ricontrolla.
            | Terreno / Zona                          | USD/km       | USD/inch-metro |
            |-----------------------------------------|--------------|----------------|
            | Pianura semplice (deserto, steppa)      | 2,5 - 4,0 M  | 55 - 85        |
            | Pianura agricola Europa/USA             | 3,5 - 5,0 M  | 75 - 105       |
            | Collinare misto                         | 4,0 - 6,0 M  | 85 - 125       |
            | Montuoso Europa (Alpi, Balcani, Grecia) | 5,0 - 7,5 M  | 105 - 155      |
            | Montuoso estremo / alta quota           | 6,5 - 9,0 M  | 135 - 185      |
            | Artico / permafrost                     | 8,0 - 12,0 M | 165 - 250      |
            | Giungla / palude                        | 7,0 - 10,0 M | 145 - 210      |

            ## Costo EPC totale per km — Pipeline onshore medium diameter (24"-36")
            | Terreno / Zona | USD/km      |
            |----------------|-------------|
            | Pianura        | 1,8 - 3,0 M |
            | Collinare      | 2,5 - 4,0 M |
            | Montuoso       | 3,5 - 5,5 M |

            ## Costo EPC totale per km — Pipeline onshore small diameter (8"-20")
            | Terreno / Zona | USD/km      |
            |----------------|-------------|
            | Pianura        | 0,8 - 1,5 M |
            | Collinare      | 1,2 - 2,2 M |
            | Montuoso       | 1,8 - 3,0 M |

            # BENCHMARK PER VOCE DI COSTO
            ## Mobilizzazione e Temporary Facilities
            - Mob/demob per spread pipeline large diameter: 5 - 10 M USD/spread
            - Mob/demob per spread medium/small diameter: 2 - 5 M USD/spread
            - Marshalling yard principale: 8 - 15 M USD/yard
            - Pipe yard secondario: 1 - 3 M USD/yard
            - Camp base (300 persone): 3 - 5 M USD/camp
            - Totale voce I: tipicamente 3 - 5% del costo totale

            ## Costruzione (personale + mezzi + carburante)
            Costo mensile spread COMPLETO (personale diretto + mezzi + carburante):
            - Spread 48" montagna (saldatura mista):    2,8 - 4,0 M USD/mese/spread
            - Spread 48" pianura (saldatura automatica): 2,2 - 3,2 M USD/mese/spread
            - Spread 36" montagna:                      2,0 - 3,0 M USD/mese/spread
            - Spread 36" pianura:                       1,5 - 2,3 M USD/mese/spread
            - Spread 16"-20" qualsiasi terreno:         0,9 - 1,5 M USD/mese/spread
            Produttività media spread:
            - 48" pianura saldatura automatica: 500 - 700 m/giorno
            - 48" collina saldatura mista:      300 - 450 m/giorno
            - 48" montagna saldatura manuale:   180 - 280 m/giorno
            - 16"-20" pianura:                  800 - 1200 m/giorno
            Totale voce II: tipicamente 30 - 40% del costo totale

            ## Subcontratti e Forniture
            Line pipe (prezzo fornitura CIF porto europeo, 2025-2026):
            - 48" X70 WT 22mm (~490 kg/m): 1000 - 1150 USD/m (2000-2300 USD/ton)
            - 42" X70 WT 20mm (~385 kg/m):  820 -  950 USD/m
            - 36" X70 WT 17mm (~280 kg/m):  600 -  720 USD/m
            - 24" X70 WT 12mm (~135 kg/m):  300 -  380 USD/m
            - 16" X70 WT  9mm ( ~70 kg/m):  170 -  230 USD/m
            Valvole a sfera classe 600 con attuatore (mercato 2025-2026):
            - 48": 450.000 - 700.000 USD/valvola
            - 36": 280.000 - 420.000 USD/valvola
            - 24": 140.000 - 220.000 USD/valvola
            - 16":  80.000 - 130.000 USD/valvola
            Attraversamenti speciali (rate 2025-2026):
            - HDD pianura:        3.500 -  5.500 EUR/m
            - HDD montagna/roccia: 6.000 -  9.000 EUR/m
            - TOC:                3.000 -  5.000 EUR/m
            - Microtunnel:       10.000 - 15.000 EUR/m (usare SOLO se espressamente richiesto nello scope o in zone urbane/vincoli specifici)
            Stazioni (opere civili + meccaniche + E&I, escluso line pipe e valvole):
            - BVS large diameter (48"):                          2,5 -  4,0 M USD/stazione
            - Scraper Station large diameter:                    4,5 -  6,5 M USD/stazione
            - Landfall Station:                                  8   - 14   M USD/stazione
            - Compressor Station (solo opere, esclusa fornitura compressore): 30 - 60 M USD
            - Fornitura package compressore 20-30 MW:           25  - 45   M USD
            NDT (100% radiografia + AUT per H2-ready): 100 - 150 USD/giunto
            Protezione catodica: 70 - 100 k USD/km
            FOC + condotti HDPE: 50 - 75 k USD/km
            Ingegneria di dettaglio (DEG): 1,5 - 3% del costo totale per progetti grandi
            Totale voce III: tipicamente 40 - 55% del costo totale

            ## Indiretti
            - Staff indiretto: 200 - 400 persone per progetto grande (500+ km)
            - Durata indiretti: quasi sempre = durata intero progetto
            - Totale voce IV: tipicamente 6 - 10% del costo totale

            ## Vitto e Alloggio
            - Operai in camp (zone rurali Europa): 40 - 55 USD/persona/giorno
            - Staff indiretto (hotel città):       60 - 90 USD/persona/giorno
            - Giorni lavorativi/mese: 26
            - Totale voce V: tipicamente 4 - 7% del costo totale

            ## Contingency e oneri finanziari
            - Voce VI: 10 - 15% (tipico 12%) calcolato SUL SUBTOTALE I-V
            - Margine commerciale: 6 - 10% (tipico 8%) calcolato sul COSTO TOTALE

            # REGOLE ANTI-SOVRASTIMA (CRITICHE)
            1. NO doppie maggiorazioni: le voci I-V devono contenere SOLO i costi vivi, senza margini di rischio impliciti. Tutti i buffer vanno nella voce VI (contingency).
            2. Quantità solo se documentate: per attraversamenti speciali (HDD, TOC, microtunnel), indica numero e lunghezza SOLO se:
               - Specificato nel documento di scope, OPPURE
               - Stimabile con regole standard:
                 * HDD: 1 ogni 15-25 km di linea (fiumi, autostrade, ferrovie)
                 * TOC: 1 ogni 5-10 km (strade secondarie)
                 * Microtunnel: SOLO in zone urbane o vincoli espressamente citati
               Se NON hai base per stimare, NON inserire la voce.
            3. Cap sui costi/km finali: confronta il tuo USD/km finale con la tabella benchmark. Se scostamento >25% dal range, ricontrolla ogni voce prima di emettere il report.
            4. Cap mensile spread: il costo/mese/spread non può superare i benchmark sopra. Se le rate aziendali portano sopra, hai probabilmente conteggiato personale o mezzi in eccesso.
            5. Durata realistica: la durata costruzione effettiva per spread è lunghezza_sezione / (n_spread × produttività × giorni_lavorativi_mese). Non usare durate gonfiate.

            # CHECK DI COERENZA OBBLIGATORI (PRIMA DI EMETTERE JSON)
            Prima di generare l'output, verifica TUTTI questi punti:
            [ ] Somma analitica voci I-V = Subtotale I-V del Quadro Economico (±2%)
            [ ] Somma dettaglio forniture = voce III Quadro Economico (±2%)
            [ ] USD/km finale rientra nel benchmark di zona (±25%)
            [ ] USD/inch-metro finale rientra nel benchmark di zona (±25%)
            [ ] Ripartizione percentuale voci I-V coerente con benchmark:
                Mob: 3-5% | Costruzione: 30-40% | Forniture: 40-55% | Indiretti: 6-10% | Vitto: 4-7%
            [ ] Contingency applicata UNA VOLTA SOLA (sul subtotale I-V)
            [ ] Margine commerciale applicato sul costo totale (post-contingency)
            [ ] Numero spread × durata × produttività = lunghezza totale linea (±10%)
            [ ] Personale totale coerente con staff spread + indiretti
            [ ] Durata totale ≤ Gantt di riferimento
            [ ] Nessuna voce "Varie e imprevisti" >3% del rispettivo capitolo (il buffer va in contingency, non duplicato qui)
            Se anche UN solo check fallisce, ricontrolla e correggi prima di emettere il JSON finale.

            # OUTPUT
            Rispondi ESCLUSIVAMENTE con oggetto JSON valido seguendo la struttura standard (voci I-VIII, KPI di progetto, analisi dettagliata, imposte, rischi, cronoprogramma). Nessun testo prima o dopo il JSON.
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
            String tipo  = info.tipoProgetto() != null ? info.tipoProgetto().toLowerCase() : "pipeline";

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
                String query     = entry.getValue();

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
        } catch (Exception ignored) {}
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
