# Enterprise Refactor — Phase 1 & 2

## Cosa è cambiato

### Phase 1 — Pipeline Engine + Unified Job Table

#### Problema risolto
I tre workflow (analisi contratti, preventivi, confronto offerte) erano implementati come tre sistemi separati e duplicati: tre controller, tre service, tre processor, tre entità JPA, tre tabelle nel DB. Aggiungere un nuovo workflow significava replicare tutta questa struttura da zero.

#### Soluzione implementata

**Nuova tabella `job`** (migration V017)
Una singola tabella sostituisce le tre tabelle specifiche a livello di API v2. Contiene:
- `workflow_id` — quale workflow è stato eseguito (`contract-risk-analysis`, `estimate-generation`, `price-comparison`)
- `sector` — settore dell'analisi (`OIL_GAS`, `PHARMA`, `LEGAL`, `CIVIL`, `FINANCE`)
- `legacy_job_id` — UUID dell'entità legacy che fa il processing reale
- `legacy_type` — discriminatore (`CONTRACT`, `ESTIMATE`, `PRICE_COMPARISON`)
- Campi standard: status, progress, currentStep, errorMessage, model, inputFilenames

**WorkflowDefinition** — `pipeline/WorkflowDefinition.java`
Record immutabile che descrive un workflow: ID, nome, settori supportati, step da eseguire, numero file min/max.

**WorkflowRegistry** — `pipeline/WorkflowRegistry.java`
Registro in memoria di tutti i workflow disponibili. Per aggiungere un nuovo workflow: un'entry qui + un PipelineStep. Nessun'altra modifica.

**PipelineContext** — `pipeline/PipelineContext.java`
Oggetto mutabile passato tra gli step. Contiene: jobId, workflowId, sector, model, file bytes, filenames, userId, attributi arbitrari per risultati intermedi.

**PipelineStep** — `pipeline/PipelineStep.java`
Interfaccia con due metodi: `stepId()` e `execute(ctx)`. Implementazioni:
- `ContractRiskStep` — delega a `ContractAnalysisService`
- `EstimateStep` — delega a `EstimateGenerationService`
- `PriceComparisonStep` — delega a `PriceComparisonService`

**PipelineEngine** — `pipeline/PipelineEngine.java`
Orchestratore centrale. Su `submit()`:
1. Valida workflowId contro WorkflowRegistry
2. Valida sector contro SectorProfileRegistry
3. Valida numero file
4. Crea record `Job` (status=PENDING)
5. Dispatcha al PipelineStep corretto
6. Salva il `legacyJobId` restituito dallo step
7. Aggiorna status a PROCESSING

Su `getStatus()`: legge il `Job`, poi sincronizza status/progress dall'entità legacy tramite `legacyJobId`.

Su `getResult()`: legge il risultato dall'entità legacy.

Su `cancel()`: cancella sia il `Job` che l'entità legacy.

**Nuovo endpoint v2** — `controller/v2/JobController.java`

```
POST   /api/v2/jobs           — invia qualsiasi workflow (multipart: files + workflowId + sector + model)
GET    /api/v2/jobs/{id}      — polling stato unificato
GET    /api/v2/jobs/{id}/result — download risultato DOCX
POST   /api/v2/jobs/{id}/cancel — cancella job
GET    /api/v2/workflows      — lista workflow disponibili (opzionale: ?sector=OIL_GAS)
GET    /api/v2/sectors        — lista settori disponibili con workflow abilitati
```

**Compatibilità v1**: gli endpoint v1 (`/api/contracts/**`, `/api/estimates/**`, `/api/price-comparison/**`) non sono stati modificati e continuano a funzionare esattamente come prima.

---

### Phase 2 — Prompt Management System + Sector Profile

#### Problema risolto
I prompt LLM erano stringhe Java `static final` all'interno dei processor. Per modificarli era necessario un redeployment. Per supportare un settore diverso (es. Pharma vs Oil & Gas) era necessario duplicare il codice con prompt diversi.

#### Soluzione implementata

**Nuova tabella `prompt_template`** (migration V019)
Ogni riga è un prompt identificato da:
- `template_key` — chiave logica (es. `contract-risk-section-analysis`)
- `sector` — settore a cui appartiene (es. `OIL_GAS`, `PHARMA`)
- `version` — versione del prompt (es. `v1`)
- `active` — flag booleano per enable/disable senza cancellare
- `role` — `SYSTEM` o `USER`
- `body` — testo completo del prompt

**Seed OIL_GAS** (migration V020)
5 prompt pre-caricati per il settore OIL_GAS:
1. `contract-risk-section-analysis` — analisi singola sezione contratto
2. `contract-risk-synthesis` — sintesi finale analisi contratto
3. `estimate-generation-system` — generazione preventivo EPC
4. `price-comparison-extraction` — estrazione dati da offerta fornitore
5. `price-comparison-synthesis` — confronto comparativo offerte

**PromptTemplateService** — `service/PromptTemplateService.java`
Risolve un prompt dato `templateKey` + `sector`. Logica di fallback:
1. Cerca prompt esatto per `templateKey` + `sector` + `active=true`
2. Se non trovato e sector ≠ OIL_GAS: usa il prompt OIL_GAS come fallback
3. Se nemmeno quello esiste: lancia `IllegalStateException` (seed mancante)

**SectorProfile** — `sector/SectorProfile.java`
Record immutabile: id, displayName, description, lista workflow abilitati.

**SectorProfileRegistry** — `sector/SectorProfileRegistry.java`
Registro in memoria dei settori supportati:
- `OIL_GAS` — tutti e 3 i workflow
- `PHARMA` — contract-risk-analysis + price-comparison
- `LEGAL` — solo contract-risk-analysis
- `CIVIL` — tutti e 3 i workflow
- `FINANCE` — contract-risk-analysis + price-comparison

**Campo `sector` sulle entità legacy** (migration V018)
Aggiunto `sector VARCHAR(50) DEFAULT 'OIL_GAS'` a `contract_analysis`, `estimate`, `price_comparison`. Le righe esistenti ricevono OIL_GAS automaticamente (backward compatible).

**Processor aggiornati**
I tre processor (`ContractAnalysisProcessor`, `EstimateGenerationProcessor`, `PriceComparisonProcessor`) leggono ora il `sector` dall'entità al momento dell'elaborazione e chiamano `promptTemplateService.resolve(key, sector)` invece di usare le costanti hardcoded.

---

## Flusso dell'app — Nuovo (v2)

```
Frontend / Client
    │
    │  POST /api/v2/jobs
    │  (files, workflowId, sector, model)
    ▼
JobController (v2)
    │
    │  submit(workflowId, sector, model, files, userId)
    ▼
PipelineEngine
    │  1. WorkflowRegistry.getOrThrow(workflowId)
    │  2. SectorProfileRegistry.getOrDefault(sector)
    │  3. Valida numero file
    │  4. Crea Job (status=PENDING) → salva in DB
    │  5. Dispatcha a PipelineStep
    ▼
PipelineStep (es. ContractRiskStep)
    │  6. Chiama ContractAnalysisService.startAnalysis(..., sector)
    ▼
ContractAnalysisService
    │  7. Crea ContractAnalysis con sector
    │  8. Chiama ContractAnalysisProcessor.processAsync() (async)
    │  9. Ritorna legacyJobId
    ▼
PipelineEngine (continua)
    │  10. Salva legacyJobId + legacyType in Job
    │  11. Aggiorna Job.status = PROCESSING
    │
    │  Ritorna Job.id al client
    ▼
ContractAnalysisProcessor (thread separato)
    │  12. Carica sector da ContractAnalysis
    │  13. PromptTemplateService.resolve("contract-risk-section-analysis", sector)
    │  14. PromptTemplateService.resolve("contract-risk-synthesis", sector)
    │  15. Esegue pipeline Map-Reduce con i prompt caricati dal DB
    │  16. Aggiorna ContractAnalysis.status = COMPLETED
    ▼
Client polling GET /api/v2/jobs/{id}
    │
    ▼
PipelineEngine.getStatus(id)
    │  - Carica Job dal DB
    │  - Legge ContractAnalysis tramite legacyJobId
    │  - Sincronizza status/progress sul Job
    │  - Ritorna JobStatusResponse unificato
```

---

## Flusso dell'app — Legacy (v1) — invariato

Il flusso v1 NON è cambiato. Gli endpoint esistenti continuano a funzionare:

```
POST /api/contracts/analyze
    → ContractAnalysisService.startAnalysis(file, model, outputFileName)
    → sector defaults to "OIL_GAS"
    → ContractAnalysisProcessor usa PromptTemplateService (nuovo, ma trasparente)
```

La sola differenza visibile nel v1: i prompt vengono ora letti dal DB invece di essere hardcoded. Il comportamento è identico perché il seed V020 contiene gli stessi prompt che erano in codice.

---

## Come aggiungere un nuovo settore (es. MINING)

1. **`SectorProfileRegistry.java`**: aggiungere entry
   ```java
   new SectorProfile("MINING", "Mining & Extraction", "...", List.of("contract-risk-analysis", "price-comparison"))
   ```

2. **`WorkflowRegistry.java`**: aggiungere "MINING" alla lista `supportedSectors` dei workflow compatibili

3. **DB migration V021**: INSERT prompt_template per sector="MINING"
   ```sql
   INSERT INTO prompt_template (template_key, workflow_id, sector, ..., body)
   VALUES ('contract-risk-section-analysis', 'contract-risk-analysis', 'MINING', ..., $$...$$);
   ```

4. **Nessun'altra modifica** — il PipelineEngine, i step, i processor sono già pronti.

---

## Come modificare un prompt senza redeployment

```sql
-- Disattiva il vecchio
UPDATE prompt_template
SET active = false
WHERE template_key = 'contract-risk-section-analysis' AND sector = 'OIL_GAS' AND version = 'v1';

-- Inserisci il nuovo
INSERT INTO prompt_template (template_key, workflow_id, sector, version, role, prompt_name, body)
VALUES ('contract-risk-section-analysis', 'contract-risk-analysis', 'OIL_GAS', 'v2', 'SYSTEM', '...', $$nuovo testo$$);
```

Alla successiva elaborazione, il nuovo prompt viene usato automaticamente.

---

## File nuovi

| File | Tipo | Scopo |
|------|------|-------|
| `entities/Job.java` | Entity JPA | Record unificato job v2 |
| `entities/PromptTemplate.java` | Entity JPA | Template prompt per settore |
| `repository/JobRepository.java` | Repository | CRUD su tabella job |
| `repository/PromptTemplateRepository.java` | Repository | Lookup prompt per key+sector |
| `pipeline/PipelineContext.java` | Value object | Dati condivisi tra step |
| `pipeline/PipelineStep.java` | Interface | Contratto per ogni step |
| `pipeline/WorkflowDefinition.java` | Record | Descrittore workflow |
| `pipeline/WorkflowRegistry.java` | Registry | Catalogo workflow |
| `pipeline/PipelineEngine.java` | Service | Orchestratore centrale v2 |
| `pipeline/steps/ContractRiskStep.java` | Step impl | Delega a ContractAnalysisService |
| `pipeline/steps/EstimateStep.java` | Step impl | Delega a EstimateGenerationService |
| `pipeline/steps/PriceComparisonStep.java` | Step impl | Delega a PriceComparisonService |
| `sector/SectorProfile.java` | Record | Descrittore settore |
| `sector/SectorProfileRegistry.java` | Registry | Catalogo settori |
| `service/PromptTemplateService.java` | Service | Risoluzione prompt per sector |
| `controller/v2/JobController.java` | Controller | API v2 unificata |
| `dto/v2/JobSubmitRequest.java` | DTO | Request submit job v2 |
| `dto/v2/JobStatusResponse.java` | DTO | Response status job v2 |
| `dto/v2/WorkflowInfoResponse.java` | DTO | Info workflow nel catalogo |
| `util/ByteArrayMultipartFile.java` | Utility | Adatta byte[] a MultipartFile |
| `V017__create_job_table.sql` | Migration | Tabella job unificata |
| `V018__add_sector_to_legacy_jobs.sql` | Migration | Campo sector sulle tabelle legacy |
| `V019__create_prompt_template_table.sql` | Migration | Tabella prompt_template |
| `V020__seed_prompt_templates_oil_gas.sql` | Migration | Seed 5 prompt OIL_GAS |

## File modificati

| File | Modifica |
|------|---------|
| `entities/ContractAnalysis.java` | Aggiunto campo `sector` (default OIL_GAS) |
| `entities/Estimate.java` | Aggiunto campo `sector` (default OIL_GAS) |
| `entities/PriceComparison.java` | Aggiunto campo `sector` (default OIL_GAS) |
| `service/ContractAnalysisService.java` | Overload `startAnalysis(..., sector)` |
| `service/estimate/EstimateGenerationService.java` | Overload `startGeneration(..., sector)` |
| `service/pricecomparison/PriceComparisonService.java` | Overload `startComparison(..., sector)` |
| `service/ContractAnalysisProcessor.java` | Usa PromptTemplateService, rimossi static final prompt |
| `service/estimate/EstimateGenerationProcessor.java` | Usa PromptTemplateService, rinominato SYSTEM_PROMPT → SYSTEM_PROMPT_FALLBACK |
| `service/pricecomparison/PriceComparisonProcessor.java` | Usa PromptTemplateService, rimossi SYSTEM_EXTRACTION e SYSTEM_COMPARISON |
| `configuration/SecurityConfig.java` | Aggiunto `/api/v2/**` con ruoli ADMIN/USER/ANALYST |
