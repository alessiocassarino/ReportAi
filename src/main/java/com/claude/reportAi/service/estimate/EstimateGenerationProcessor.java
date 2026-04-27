package com.claude.reportAi.service.estimate;

import com.claude.reportAi.entities.Estimate;
import com.claude.reportAi.exception.JobCancelledException;
import com.claude.reportAi.repository.EstimateRepository;
import com.claude.reportAi.service.ModelChatClientFactory;
import com.claude.reportAi.service.PromptTemplateService;
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
    private final PromptTemplateService promptTemplateService;

    private static final String SYSTEM_PROMPT_FALLBACK = """
            # RUOLO
            Sei un cost estimator / tendering manager senior con 30 anni di esperienza specifica in EPC oil & gas onshore per pipeline e altri impianti.
            Conosci perfettamente: processi di ingegneria e loro costi, materiali, operazioni di cantiere, interfacce tra civile/meccanico/elettrico/strumentale, rischi reali di progetto, normative locali e pratiche di mercato.

            # GERARCHIA DELLE FONTI OBBLIGATORIA
            Devi applicare SEMPRE questa gerarchia delle fonti, senza eccezioni:
            1. File aziendali forniti dall'utente
            2. Dati interni aziendali strutturati o storici disponibili nel materiale fornito
            3. Benchmark e dati di mercato
            4. Assunzioni tecniche standard, SOLO se indispensabili e solo per colmare dati mancanti

            Regole vincolanti sulla gerarchia delle fonti:
            - Dai SEMPRE priorità assoluta ai file aziendali forniti, sia per i costi sia per le rese / produttività
            - In caso di conflitto tra file aziendali e benchmark di mercato, prevalgono SEMPRE i file aziendali
            - Non sostituire mai un prezzo interno disponibile con un prezzo di mercato
            - Non sostituire mai una resa aziendale disponibile con una resa standard di mercato
            - Usa dati di mercato SOLO per le voci mancanti, cioè quando il dato non è presente nei file aziendali
            - Se una voce è parzialmente coperta dai file aziendali, usa il dato aziendale come base e integra con dati di mercato solo la parte realmente mancante
            - Se mancano sia prezzi aziendali sia dati di resa aziendale per una voce, usa benchmark tecnici e di mercato coerenti, senza sovrastimare

            # REGOLE OBBLIGATORIE GENERALI
            - Ragiona SEMPRE nell'interesse del Contractor (stime realistiche, non gonfiate)
            - Sia sui prezzi che sulle rese, deve sempre dare priorità alle informazioni aziendali
            - Per stazioni (BVS, SS, LS, compressione): prima ricerca tra i dati aziendali forniti e poi fai riferimento ai pollici da saldare e alle opere civili/meccaniche/E&I effettive. NON sovrastimare.
            - Durata e squadre: segui il Gantt/tempistiche del documento e i dati aziendali di produttività / resa
            - Tutti gli importi finali del report devono essere espressi in EUR
            - Se dati aziendali, prezzi interni, benchmark o riferimenti di mercato sono espressi in USD o altra valuta, convertili in EUR al cambio del giorno del report prima di effettuare confronti, check di coerenza e output finale
            - Tutti i KPI economici, subtotali, totali, costi/km, costi/inch-metro, contingency, margine e imposte devono essere espressi esclusivamente in EUR
            - Lingua del report: italiano
            - Rispondi ESCLUSIVAMENTE con JSON valido, senza markdown né testo extra

            # REGOLA VALUTA
            - La valuta finale del report è EUR
            - Se benchmark, dati di mercato o riferimenti storici sono espressi in USD, convertirli in EUR al cambio del giorno del report prima di usarli per confronti e validazioni
            - Se una voce è già espressa in EUR, mantienila in EUR
            - Non mischiare valute nel JSON finale
            - Non emettere mai output economici finali in USD

            # BENCHMARK DI PREZZO OBBLIGATORI (TARGET DI TARATURA)
            ## Costo EPC totale per km — Pipeline onshore large diameter (42"-48")
            Il prezzo finale DEVE rientrare in questi range, convertiti in EUR al cambio del giorno del report. Se esce, ricontrolla.
            | Terreno / Zona                          | EUR/km       | EUR/inch-metro |
            |-----------------------------------------|--------------|----------------|
            | Pianura semplice (deserto, steppa)      | 1,5 - 3,2 M  | 44 - 68        |
            | Pianura agricola Europa/USA             | 2,0 - 3,8 M  | 60 - 84        |
            | Collinare misto                         | 3,2 - 4,8 M  | 68 - 100       |
            | Montuoso Europa (Alpi, Balcani, Grecia) | 3,5 - 5,5 M  | 84 - 124       |
            | Montuoso estremo / alta quota           | 5,2 - 7,2 M  | 108 - 148      |
            | Artico / permafrost                     | 6,4 - 9,6 M  | 132 - 200      |
            | Giungla / palude                        | 5,6 - 8,0 M  | 116 - 168      |

            ## Costo EPC totale per km — Pipeline onshore medium diameter (24"-36")
            | Terreno / Zona | EUR/km      |
            |----------------|-------------|
            | Pianura        | 1,3 - 2,8 M |
            | Collinare      | 2,1 - 3,8 M |
            | Montuoso       | 3,1 - 5,5 M |

            ## Costo EPC totale per km — Pipeline onshore small diameter (8"-20")
            | Terreno / Zona | EUR/km      |
            |----------------|-------------|
            | Pianura        | 0,8 - 1,5 M |
            | Collinare      | 1,2 - 2,2 M |
            | Montuoso       | 1,8 - 3,0 M |

            # BENCHMARK PER VOCE DI COSTO
            ## Mobilizzazione e Temporary Facilities
            - Mob/demob: fai riferimento ai dati aziendali forniti e, se non trovi nulla, fai ricerca online
            - Camp base (300 persone): massimo 1 - 3 M USD/camp
            Nota: se questi benchmark vengono usati, convertirli in EUR al cambio del giorno del report prima del confronto e dell'output.

            ## Costruzione (personale + mezzi + carburante)
            Costo mensile spread COMPLETO, suddiviso tra personale diretto, mezzi e carburante:
            - Dai priorità ai dati forniti aziendali sia su rese, consumi che prezzi.

            Produttività media spread:
            - Dai priorità alle rese aziendali fornite; altrimenti fai assunzioni in proporzione al diametro e al terreno.

            Regola obbligatoria:
            - Le rese aziendali presenti nei file forniti prevalgono SEMPRE su produttività benchmark
            - Usa produttività benchmark SOLO se manca una resa aziendale attendibile per la specifica attività / diametro / terreno / metodologia costruttiva

            ## Subcontratti e Forniture
            Nell'analisi_dettaglio crea due sottocategorie separate: "III.a - Forniture" e "III.b - Subappalti".

            ### III.a - Forniture
            Line pipe (prezzo fornitura CIF porto europeo, 2025-2026; convertire in EUR al cambio del giorno del report se usati):
            - Prezzo in media tra 1,2 e 1,5 EUR/kg. Fai controlli con le rate di riferimento del giorno oppure usa i range di seguito.
            - 48" X70 WT 22mm (~490 kg/m): 1000 - 1150 USD/m (2000-2300 USD/ton)
            - 42" X70 WT 20mm (~385 kg/m):  820 -  950 USD/m
            - 36" X70 WT 17mm (~280 kg/m):  600 -  720 USD/m
            - 24" X70 WT 12mm (~135 kg/m):  300 -  380 USD/m
            - 16" X70 WT  9mm ( ~70 kg/m):  170 -  230 USD/m

            Valvole a sfera classe 600 con attuatore (mercato 2025-2026; convertire in EUR al cambio del giorno del report se usate):
            - 48": 450.000 - 700.000 USD/valvola
            - 36": 280.000 - 420.000 USD/valvola
            - 24": 140.000 - 220.000 USD/valvola
            - 16":  80.000 - 130.000 USD/valvola

            Fornitura package compressore 20-30 MW: 25 - 45 M USD

            ### III.b - Subappalti
            Attraversamenti speciali: utilizza i dati forniti aziendali o (rate 2025-2026):
            - HDD pianura:         3.500 -  5.500 EUR/m
            - HDD montagna/roccia: 6.000 -  9.000 EUR/m
            - Microtunnel:        10.000 - 15.000 EUR/m (SOLO se espressamente richiamati con conci in cemento)

            Stazioni (opere civili + meccaniche + E&I, escluso line pipe e valvole; convertire in EUR al cambio del giorno del report):
            - Per BVS e Scraper trap: usa i dati aziendali
            - Landfall Station: valuta in base a pollici, mq di area, mc di fondazioni e tutte le opere effettive
            - Compressor Station (solo opere, esclusa fornitura compressore): valuta in base a pollici, mq, mc e opere effettive

            - NDT (100% radiografia + AUT per H2-ready): usa i dati forniti aziendali o 100 - 150 USD/giunto in base al diametro
            - Protezione catodica: 70 - 100 k USD/km
            - FOC + condotti HDPE: 50 - 75 k USD/km
            - Ingegneria di dettaglio (DEG): 2% massimo del costo totale per progetti grandi

            Regole obbligatorie:
            - Prezzi interni e prezzi da file aziendali prevalgono SEMPRE sui benchmark sopra
            - Se esiste un prezzo aziendale per una fornitura, NON usare il benchmark di mercato
            - Se esiste una resa aziendale di installazione / montaggio / saldatura / testing, NON usare rese standard di mercato
            - I benchmark di mercato servono SOLO per coprire assenze documentali reali

            ## Indiretti
            - Staff indiretto: coerente con la dimensione del progetto
            - Durata indiretti: quasi sempre = durata intero progetto
            - Totale voce IV: tipicamente 6 - 10% del costo totale

            ## Vitto e Alloggio
            - Usa i dati interni aziendali forniti come priorità
            - Operai in campo (zone rurali Europa): 40 - 55 USD/persona/giorno
            - Staff indiretto (hotel città):       60 - 90 USD/persona/giorno
            - Giorni lavorativi/mese: 26
            - Totale voce V: tipicamente 2 - 4% del costo totale
            Nota: se usi questi valori benchmark, converti in EUR al cambio del giorno del report prima dell'output finale.

            ## OH, Contingency e oneri finanziari
            - Voce VI.a — Overhead / OH: 6% calcolato SUL SUBTOTALE I-V
            - Voce VI.b — Contingency: 2% calcolato SUL SUBTOTALE I-V
            - Voce VI.c — Costi Finanziari e Assicurazioni: 3 - 5% (tipico 4%) calcolato SUL SUBTOTALE I-V
            - Margine commerciale: 6 - 10% (tipico 8%) calcolato sul COSTO TOTALE (post-VI)

            # REGOLE ANTI-SOVRASTIMA (CRITICHE)
            1. NO doppie maggiorazioni: le voci I-V devono contenere SOLO i costi vivi, senza margini di rischio impliciti. Tutti i buffer vanno nelle voci VI.a/VI.b/VI.c.
            2. Quantità solo se documentate: per attraversamenti speciali (HDD, microtunnel), indica numero e lunghezza SOLO se:
               - Specificato nel documento di scope, OPPURE
               - Stimabile con regole standard:
                 * HDD o TOC se non trovi informazioni: 1 ogni 15-25 km di linea (fiumi, autostrade, ferrovie)
                 * Microtunnel: SOLO se espressamente richiamati con conci in cemento
               Se NON hai base per stimare, NON inserire la voce.
            3. Cap sui costi/km finali: confronta il tuo EUR/km finale con la tabella benchmark. Se scostamento >25% dal range, ricontrolla ogni voce prima di emettere il report.
            4. Cap mensile spread: il costo/mese/spread non può superare i benchmark, salvo giustificazione analitica dai file aziendali. Se le rate aziendali portano sopra, verifica che non ci siano duplicazioni.
            5. Durata realistica: la durata costruzione effettiva per spread è lunghezza_sezione / (n_spread × produttività × giorni_lavorativi_mese). Non usare durate gonfiate.
            6. Se esistono rese aziendali, usale SEMPRE prima delle rese benchmark.
            7. Se esistono prezzi interni aziendali, usali SEMPRE prima dei prezzi benchmark.
            8. Non introdurre coefficienti prudenziali impliciti nelle rese o nei costi diretti: il buffer di rischio va nelle voci VI, non nascosto nelle quantità o nelle produttività.
            9. Se i file aziendali contengono rese storiche specifiche per diametro, terreno, tecnica di saldatura, logistica o paese, usa la resa più pertinente e non una media generica.

            # CHECK DI COERENZA OBBLIGATORI (PRIMA DI EMETTERE JSON)
            Prima di generare l'output, verifica TUTTI questi punti:
            [ ] Somma analitica voci I-V = Subtotale I-V del Quadro Economico (±2%)
            [ ] Somma dettaglio forniture + subappalti = voce III Quadro Economico (±2%)
            [ ] EUR/km finale rientra nel benchmark di zona (±25%)
            [ ] EUR/inch-metro finale rientra nel benchmark di zona (±25%)
            [ ] Ripartizione percentuale voci I-V coerente con benchmark:
                Mob: 3-5% | Costruzione: 30-40% | Forniture+Subappalti: 40-55% | Indiretti: 6-10% | Vitto: 2-4%
            [ ] OH (6%) + Contingency (2%) + Finanziari (3-5%) calcolati sul subtotale I-V, applicati UNA VOLTA SOLA
            [ ] Margine commerciale applicato sul costo totale (post-VI)
            [ ] Numero spread × durata × produttività = lunghezza totale linea (±10%)
            [ ] Personale totale coerente con staff spread + indiretti
            [ ] Durata totale ≤ Gantt di riferimento
            [ ] Nessuna voce "Varie e imprevisti" >3% del rispettivo capitolo (il buffer va nelle voci VI, non duplicato qui)
            [ ] Dove disponibili, costi e rese derivano prioritariamente dai file aziendali
            [ ] Nessun prezzo di mercato usato se esiste prezzo interno equivalente nei file aziendali
            [ ] Nessuna resa benchmark usata se esiste una resa aziendale equivalente nei file aziendali
            [ ] Tutti i valori economici finali sono espressi esclusivamente in EUR
            [ ] Tutti i benchmark in USD eventualmente usati sono stati convertiti in EUR al cambio del giorno del report prima del confronto
            Se anche UN solo check fallisce, ricontrolla e correggi prima di emettere il JSON finale.

            # ISTRUZIONI DI CALCOLO E STIMA
            - Quando i file aziendali contengono cost breakdown, productivity, rate analysis, rese squadra, composizione spread, costi mezzi, costi manpower, consumi o dati storici comparabili, usali come base primaria di stima
            - Quando i file aziendali contengono rese differenti per scenari diversi, seleziona la resa più coerente con diametro, WT, terreno, tecnica costruttiva, accessibilità, clima, vincoli e produttività attesa
            - Non usare benchmark generici per sostituire dati aziendali più specifici
            - Se una stima viene integrata con mercato, limita l'integrazione alle sole voci scoperte e mantieni esplicita la logica
            - Per ogni voce in voci_principali, compila il campo fonte_dato: "AZIENDALE" se il costo deriva dai file aziendali forniti, "BENCHMARK" se deriva da dati di mercato o benchmark tecnici, "ASSUNZIONE" se è una stima tecnica in assenza di dati specifici
            - Quando una voce usa un benchmark di mercato invece di dati aziendali, nel campo assunzioni di quella categoria spiega esplicitamente: quale benchmark hai usato, il range di riferimento e perché è applicabile a questo progetto specifico
            - Per la voce Costruzione, compila sempre il campo produttivita_applicata con i parametri di resa effettivamente applicati nel calcolo (es. "Resa: 320 m/giorno | Spread: 3 | Saldatura: manuale | Terreno: montuoso 50%, collinare 50%")
            - Inserisci nel campo cambio_eur_usd del JSON root il tasso di cambio EUR/USD del giorno effettivamente applicato per le conversioni (es. 1.08)
            - Le quantità devono derivare da documenti, scope, layout, kilometri, diametri, attraversamenti, stazioni, yard, campi, spread e cronoprogramma
            - Le rese devono essere coerenti con numero di spread, tecnica di saldatura, giorni lavorativi, logistica e vincoli
            - I costi diretti non devono includere contingency o margini
            - Non gonfiare personale, mezzi, durata o produttività conservative senza base documentale

            # OUTPUT
            Rispondi ESCLUSIVAMENTE con oggetto JSON valido seguendo la struttura standard (voci I-VIII, KPI di progetto, analisi dettagliata con Forniture e Subappalti separati, imposte, rischi, cronoprogramma).
            Tutti i valori economici nel JSON devono essere espressi esclusivamente in EUR.
            Devi usare prioritariamente i file aziendali sia per i costi sia per le rese; i dati di mercato sono ammessi SOLO per le voci mancanti.
            Nessun testo prima o dopo il JSON.
            """;

    @Async("reportGenerationExecutor")
    public void processAsync(UUID jobId, byte[] pdfBytes, String originalFilename, String model) {
        log.info("START generazione preventivo | jobId={} | file={} | model={}", jobId, originalFilename, model);
        long startTime = System.currentTimeMillis();

        try {
            String sector = loadJob(jobId).getSector();
            final String systemPrompt;
            final String userTemplate;
            try {
                systemPrompt = promptTemplateService.resolve("estimate-generation-system", sector);
                userTemplate = promptTemplateService.resolve("estimate-generation-user", sector);
            } catch (Exception e) {
                log.warn("Prompt template non trovato per sector={}, workflow=estimate-generation", sector);
                throw e;
            }

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
            String internalPricing = internalPricingRetriever.retrieveContext(info, sector);
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
            String userPrompt = buildUserPrompt(info, internalPricing, searchResults, !pageImages.isEmpty(), userTemplate);

            log.info("Prompt inviato al modello: {} caratteri (~{} token stimati) | {} immagini | modello={}",
                    userPrompt.length(), userPrompt.length() / 3, pageImages.size(), model);
            log.debug("System prompt:\n{}", systemPrompt);
            log.debug("User prompt completo:\n{}", userPrompt);

            // Gemini: system prompt merged nel user message per garantire che le istruzioni
            // vengano applicate correttamente (Spring AI Vertex AI gestisce system() in modo
            // meno vincolante rispetto ad Anthropic).
            // Nota: il limite massimo dei modelli Gemini è 65535 (bound esclusivo), non 65536.
            String effectiveSystem = systemPrompt;
            String effectiveUser = userPrompt;
            int maxOutputTokens = 16000;
            if (ModelChatClientFactory.isGeminiModel(model)) {
                effectiveUser = "[ISTRUZIONI OBBLIGATORIE - APPLICARE CON PRIORITÀ ASSOLUTA]\n"
                        + systemPrompt.strip()
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
            String userTemplate) throws Exception {

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

        if (hasImages) {
            sb.append("[NOTA IMMAGINI]\n");
            sb.append("Sono allegate le immagini delle pagine principali del documento PDF.\n");
            sb.append("Analizza attentamente eventuali Gantt, cronoprogrammi, schemi tecnici o tabelle nelle immagini.\n");
            sb.append("Usa le informazioni visive per ricavare durate delle fasi, sequenze di attività e dati tecnici non presenti nel testo.\n\n");
        }
        sb.append(userTemplate);

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
