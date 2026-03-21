# ReportAI - Sistema di Generazione Report Intelligenti con IA

## 📋 Panoramica

**ReportAI** è un'applicazione Spring Boot che genera report aziendali intelligenti integrando:
- **Claude API** (Anthropic) per la generazione di testo IA
- **Ollama** per gli embedding locali (vettoriali)
- **PostgreSQL + PGVector** per il knowledge base vettoriale
- **Documento RAG (Retrieval Augmented Generation)** per contesto contestuale
- **Ricerca Web** opzionale per integrare informazioni esterne
- **Esportazione multi-formato** (CSV, XLSX, DOCX)

L'applicazione implementa un flusso di elaborazione orchestrato che:
1. Carica documenti nel knowledge base
2. Recupera documenti pertinenti dal database vettoriale
3. Opzionalmente esegue ricerche web
4. Genera report con Claude AI
5. Esporta il report nel formato richiesto

---

## 🏗️ Architettura e Componenti

### Struttura dei Componenti

```
ReportAI
├── Controller (API REST)
│   ├── ReportController → Generazione e download report
│   └── StoredFileController → Upload documenti
├── Service (Business Logic)
│   ├── ReportOrchestratorService → Orchestrazione flusso
│   ├── ReportGenerationService → Generazione testo con Claude
│   ├── StoredFileService → Ingestione documenti
│   ├── VectoreStoreService → Ricerca nel knowledge base
│   ├── ReportExportService → Esportazione in vari formati
│   └── WebSearchService → Ricerca web
├── Entity (Modello Dati)
│   └── StoredFile → Documento memorizzato
├── Repository (Accesso Dati)
│   ├── StoredFileRepository → Query su file memorizzati
│   └── ExcelTemplateRepository → Gestione template Excel
├── DTO (Data Transfer Objects)
│   ├── ReportRequest → Richiesta generazione report
│   ├── ReportResponse → Risposta con report generato
│   ├── DocumentUploadResponse → Risposta upload
│   └── ReportTableResponse → Struttura tabellare
└── Configuration
    └── AiConfig → Setup ChatClient Claude
```

### Flusso di Elaborazione

```
User Request
    ↓
POST /api/reports/generate (ReportRequest)
    ↓
ReportOrchestratorService.generate()
    ├─ VectoreStoreService.searchRelevantDocuments() → Ricerca Knowledge Base
    ├─ WebSearchService.search() (opzionale) → Ricerca Web
    └─ ReportGenerationService.generateReport() → Claude AI
        ↓
    Genera contenuto
        ↓
    ReportExportService.export() → CSV/XLSX/DOCX (opzionale)
        ↓
Response (ReportResponse)
    ├─ status: "OK"
    ├─ foundInKnowledgeBase: boolean
    ├─ webSearchUsed: boolean
    ├─ answer: String (testo report)
    ├─ fileName: String (se esportato)
    └─ downloadUrl: String (se esportato)
```

---

## 🔌 API REST

### 1. Generazione Report

**Endpoint:** `POST /api/reports/generate`

**Content-Type:** `application/json`

**Request Body:**
```json
{
  "prompt": "Analizza le tendenze di vendita del Q4 2024 basandoti sulla documentazione caricata",
  "format": "XLSX",
  "allowWebSearch": true
}
```

**Parametri:**

| Nome | Tipo | Obbligatorio | Descrizione |
|------|------|--------------|-------------|
| `prompt` | String | ✅ | Istruzioni per la generazione del report. Max 2000 caratteri consigliati. |
| `format` | String | ❌ | Formato esportazione: `JSON` (default), `CSV`, `XLSX`, `DOCX` |
| `allowWebSearch` | Boolean | ❌ | Abilita ricerca web se knowledge base insufficiente (default: `true`) |

**Response (200 OK):**
```json
{
  "status": "OK",
  "foundInKnowledgeBase": true,
  "webSearchUsed": false,
  "answer": "Analisi dettagliata del Q4 2024...",
  "fileName": "a1b2c3d4-e5f6-g7h8-i9j0-k1l2m3n4o5p6.xlsx",
  "downloadUrl": "/api/reports/download/a1b2c3d4-e5f6-g7h8-i9j0-k1l2m3n4o5p6.xlsx"
}
```

**Response Fields:**

| Campo | Tipo | Descrizione |
|-------|------|-------------|
| `status` | String | Stato dell'operazione ("OK", "ERROR") |
| `foundInKnowledgeBase` | Boolean | `true` se documenti rilevanti trovati nel DB vettoriale |
| `webSearchUsed` | Boolean | `true` se è stata eseguita ricerca web |
| `answer` | String | Testo completo del report generato |
| `fileName` | String | Nome file generato (UUID + estensione). `null` se formato JSON |
| `downloadUrl` | String | URL relativo per il download. `null` se formato JSON |

**Logica Decisionale:**
- Se `foundInKnowledgeBase=true` → Priorità al knowledge base, ricerca web non eseguita
- Se `foundInKnowledgeBase=false` E `allowWebSearch=true` → Esegui ricerca web
- Se `foundInKnowledgeBase=false` E `allowWebSearch=false` → Nessun contesto aggiuntivo

---

### 2. Download Report

**Endpoint:** `GET /api/reports/download/{fileName}`

**Path Parameters:**

| Nome | Descrizione |
|------|-------------|
| `fileName` | UUID + estensione generato dalla POST (es: `a1b2c3d4-e5f6-g7h8-i9j0-k1l2m3n4o5p6.xlsx`) |

**Response:**
- **Status:** 200 OK
- **Content-Type:** 
  - `text/csv` per .csv
  - `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` per .xlsx
  - `application/vnd.openxmlformats-officedocument.wordprocessingml.document` per .docx
- **Body:** File binario

**Esempio:**
```bash
curl -O -J http://localhost:8080/api/reports/download/a1b2c3d4-e5f6-g7h8-i9j0-k1l2m3n4o5p6.xlsx
```

---

### 3. Upload Documenti

**Endpoint:** `POST /api/documents/upload`

**Content-Type:** `multipart/form-data`

**Request:**
```
Form Field: "files" (array di MultipartFile)
File types supportati: PDF, DOCX, XLS, XLSX, TXT, DOC, RTF, PPT, PPTX, ODP, etc. (Tika)
Max file size: 50MB per file
Max request size: 200MB totale
```

**Response (200 OK):**
```json
[
  {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "originalFilename": "report_vendite.pdf",
    "status": "INDEXED"
  },
  {
    "id": "550e8400-e29b-41d4-a716-446655440001",
    "originalFilename": "dati_cliente.xlsx",
    "status": "ALREADY_EXISTS"
  }
]
```

**Response Fields:**

| Campo | Tipo | Descrizione |
|-------|------|-------------|
| `id` | UUID | Identificativo univoco del documento memorizzato |
| `originalFilename` | String | Nome file originale caricato |
| `status` | String | `INDEXED` (nuovo documento indicizzato), `ALREADY_EXISTS` (duplicato rilevato via SHA256) |

**Logica Upload:**
1. Validazione file (non vuoto)
2. Calcolo SHA256 del contenuto
3. Verifica duplicati nel DB (stessi contenuti non vengono reindicizzati)
4. Salvataggio file su disco in `./data/uploads/`
5. Estrazione testo con Apache Tika
6. Suddivisione in chunk tokenizzati (300 token, overlap 50)
7. Generazione embedding con Ollama
8. Memorizzazione vettori in PostgreSQL/PGVector

---

## 📊 Modello Dati

### Entità: StoredFile

```sql
CREATE TABLE stored_file (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    original_filename VARCHAR NOT NULL,
    content_type VARCHAR,
    sha256 VARCHAR(64) NOT NULL UNIQUE,
    size_bytes BIGINT NOT NULL,
    storage_path VARCHAR NOT NULL,
    extracted_text TEXT,
    metadata_json TEXT,
    extraction_status VARCHAR NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

**Campi:**

| Campo | Tipo | Descrizione |
|-------|------|-------------|
| `id` | UUID | Chiave primaria generata automaticamente |
| `original_filename` | String | Nome file originale caricato |
| `content_type` | String | MIME type (es: "application/pdf") |
| `sha256` | String | Hash SHA256 del contenuto (univoco, per deduplicazione) |
| `size_bytes` | Long | Dimensione file in byte |
| `storage_path` | String | Percorso assoluto file su disco |
| `extracted_text` | Text | Testo estratto da Tika |
| `metadata_json` | Text | Metadati in JSON (sourcePath, reader, docCount) |
| `extraction_status` | String | Stato estrazione ("DONE", "ERROR") |
| `created_at` | Timestamp | Data/ora caricamento |

### Vector Store: PGVector (PostgreSQL)

```sql
CREATE TABLE vector_store (
    id SERIAL PRIMARY KEY,
    content TEXT NOT NULL,
    metadata JSONB,
    embedding vector(1024),
    USING ivfflat (embedding cosine_ops)
);
```

**Configurazione:**
- Dimensioni embedding: **1024** (Ollama mxbai-embed-large)
- Distanza: **COSINE_DISTANCE**
- Indice: IVFFlat per search efficiente

---

## 🔧 Servizi e Logica di Business

### ReportOrchestratorService
**Responsabilità:** Orchestrazione del flusso principale

**Metodo Principale:** `generate(ReportRequest request) → ReportResponse`

**Passi:**
1. Ricerca documenti nel knowledge base tramite `VectoreStoreService`
2. Valuta se ricerca web è necessaria:
   - Se documenti trovati → Priorità al knowledge base
   - Se no documenti E `allowWebSearch=true` → Ricerca web
   - Se no documenti E `allowWebSearch=false` → Nessun contesto esterno
3. Invoca `ReportGenerationService` con prompt, documenti e risultati web
4. Valuta formato export richiesto
5. Se export → Invoca `ReportExportService`
6. Restituisce `ReportResponse` con URL download (se applicabile)

---

### ReportGenerationService
**Responsabilità:** Generazione contenuto con Claude AI

**Metodo Principale:** `generateReport(String userPrompt, List<Document> vectorDocs, List<String> webResults, boolean foundInKnowledgeBase) → String`

**System Prompt (Standard):**
```
Sei un assistente professionale per la generazione di report aziendali.
Rispondi in italiano, con tono professionale, chiaro, dettagliato e strutturato.
Regole:
- Usa prioritariamente il knowledge base interno.
- Se il knowledge base non contiene informazioni sufficienti, dichiaralo esplicitamente.
- Se sono presenti risultati web, usali come integrazione esterna.
- Non inventare dati mancanti.
- Organizza la risposta in: sintesi, dettagli, osservazioni, conclusioni.
- Se richiesto un report tabellare, prepara i dati in modo compatibile con CSV/XLSX.
```

**User Message Costruito:**
```
Prompt utente: [USER PROMPT]

Informazioni trovate nel knowledge base: [SI/NO]

Contesto dal knowledge base interno:
[TESTO ESTRATTO DA DOCUMENTI]

Contesto da ricerca web:
[RISULTATI WEB O "Nessuna ricerca web utilizzata."]
```

**Parametri Claude:**
- Model: `claude-sonnet-4-6`
- Temperature: `0.2` (bassa creatività, risposte consistenti)
- Max Tokens: `1536` (massimo output)

---

### StoredFileService
**Responsabilità:** Ingestione e indicizzazione documenti

**Metodo Principale:** `ingest(MultipartFile file) → DocumentUploadResponse`

**Passi:**
1. Validazione file (non vuoto)
2. Calcolo SHA256 del contenuto
3. Verifica duplicati nel repository
4. Se duplicato → Restituisci status "ALREADY_EXISTS"
5. Se nuovo:
   - Generazione UUID
   - Salvataggio file in `./data/uploads/[UUID]_[FILENAME]`
   - Estrazione testo con Apache Tika
   - Creazione entity StoredFile
   - Salvataggio metadati JSON
6. Suddivisione testo in chunk:
   - **Chunk size:** 300 token
   - **Overlap:** 50 token (per continuità)
   - **Min size:** 10 caratteri
   - **Max size:** 1000 caratteri
7. Generazione embedding per ogni chunk via Ollama
8. Memorizzazione vettori in PostgreSQL
9. Restituisci status "INDEXED"

**Estrazione Testo (Tika):**
- Formati supportati: PDF, DOCX, XLSX, PPT, ODT, RTF, TXT, etc.
- Metadati acquisiti: sourcePath, reader, docCount

---

### ReportExportService
**Responsabilità:** Esportazione report in vari formati

**Metodo Principale:** `export(String content, String format) → String`

**Formati Supportati:**

#### CSV (Comma-Separated Values)
- Separatore: `;` (punto e virgola, per compatibilità con locale italiano)
- Encoding: UTF-8
- Escape: "" (virgolette doppie)
- Rilevamento tabelle: Markdown table syntax (`| header | value |`)
- Se no tabelle: Fallback su testo linea per linea

**Esempio Output:**
```csv
"Descrizione";"Valore"
"Vendite Q4";"€ 1.500.000"
"Incremento YoY";"12%"
```

#### XLSX (Excel Spreadsheet)
- Formato: OpenXML (.xlsx)
- Libreria: Apache POI (XSSFWorkbook)
- Sheet name: "Report"
- Features:
  - Auto-sizing colonne
  - Parsing Markdown table
  - Fallback testo su singola colonna se no tabelle
- Max righe/colonne: limiti Excel standard

**Esempio Output:**
- Header: `| Descrizione | Valore |`
- Rows: `| Vendite Q4 | € 1.500.000 |`

#### DOCX (Word Document)
- Formato: OpenXML (.docx)
- Libreria: Apache POI (XWPFDocument)
- Features:
  - Titolo: "Report"
  - Paragrafi: separazione su doppio newline
  - Font: default Word

**Algoritmo Parsing Tabelle Markdown:**
```
Cerca linee con format: |cell1|cell2|cell3|
Se trovate almeno 2 righe (header + separator):
  - Parse header: estrai celle tra |
  - Skip separator row (-----|-------|)
  - Parse data rows
Se < 2 righe:
  - Fallback a testo puro
```

**Nomi File:**
- Formato: `[UUID].[ESTENSIONE]`
- Esempio: `550e8400-e29b-41d4-a716-446655440000.xlsx`

---

### VectoreStoreService
**Responsabilità:** Ricerca nel knowledge base vettoriale

**Metodo Principale:** `searchRelevantDocuments(String userPrompt) → List<Document>`

**Logica:**
1. Genera embedding del prompt utente via Ollama
2. Ricerca i vettori più simili in PostgreSQL/PGVector usando COSINE_DISTANCE
3. Restituisce documenti recuperati con metadati (fileId, filename, contentType, sha256, chunkIndex)

**Parametri Ricerca:**
- Numero risultati: configurabile (di default, dipende dall'implementazione)
- Soglia similarità: configurabile

---

### WebSearchService
**Responsabilità:** Ricerca web esterna

**Metodo Principale:** `search(String userPrompt) → List<String>`

**Logica:**
- Esegue ricerca web (API/libreria non specificate nel codice)
- Restituisce lista di risultati (snippet o URL)
- Usata solo se knowledge base vuoto e `allowWebSearch=true`

---

## 🌍 Variabili di Ambiente

### Configurazione (application.properties)

#### Database PostgreSQL
```properties
spring.datasource.url=jdbc:postgresql://localhost:5400/vector_db
spring.datasource.username=postgres
spring.datasource.password=root
spring.datasource.driver-class-name=org.postgresql.Driver
```

**Variabili:**
- `SPRING_DATASOURCE_URL` → URL PostgreSQL (default: `localhost:5400`)
- `SPRING_DATASOURCE_USERNAME` → Utente DB (default: `postgres`)
- `SPRING_DATASOURCE_PASSWORD` → Password DB (default: `root`)

#### JPA/Hibernate
```properties
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect
```

**Valori possibili per `ddl-auto`:**
- `create`: Crea tabelle da zero (attenzione: cancella dati)
- `create-drop`: Crea + drop on shutdown
- `update`: Aggiorna schema (non cancella dati)
- `validate`: Solo validazione

#### Claude API (Anthropic)
```properties
spring.ai.anthropic.api-key=sk-ant-api03-...
spring.ai.anthropic.chat.options.model=claude-sonnet-4-6
spring.ai.anthropic.chat.options.temperature=0.2
spring.ai.anthropic.chat.options.max-tokens=1536
```

**Variabili:**
- `SPRING_AI_ANTHROPIC_API_KEY` → API Key Anthropic (ottenibile da console.anthropic.com)
- `SPRING_AI_ANTHROPIC_CHAT_OPTIONS_MODEL` → Modello Claude (es: claude-opus, claude-sonnet-4-6)
- `SPRING_AI_ANTHROPIC_CHAT_OPTIONS_TEMPERATURE` → 0.0-1.0 (0=deterministico, 1=creativo)
- `SPRING_AI_ANTHROPIC_CHAT_OPTIONS_MAX_TOKENS` → Max token output

#### Ollama (Embedding)
```properties
spring.ai.model.embedding=ollama
spring.ai.ollama.base-url=http://localhost:11434
spring.ai.ollama.embedding.options.model=mxbai-embed-large:latest
```

**Variabili:**
- `SPRING_AI_OLLAMA_BASE_URL` → URL Ollama (default: `http://localhost:11434`)
- `SPRING_AI_OLLAMA_EMBEDDING_OPTIONS_MODEL` → Modello embedding (default: `mxbai-embed-large:latest`)

#### PGVector Configuration
```properties
spring.ai.vectorstore.pgvector.initialize-schema=true
spring.ai.vectorstore.pgvector.dimensions=1024
spring.ai.vectorstore.pgvector.table-name=vector_store
spring.ai.vectorstore.pgvector.distance-type=COSINE_DISTANCE
```

**Variabili:**
- `SPRING_AI_VECTORSTORE_PGVECTOR_DIMENSIONS` → Dimensioni embedding (default: 1024 per mxbai)
- `SPRING_AI_VECTORSTORE_PGVECTOR_TABLE_NAME` → Nome tabella (default: `vector_store`)
- `SPRING_AI_VECTORSTORE_PGVECTOR_DISTANCE_TYPE` → Metrica distanza: `COSINE_DISTANCE`, `EUCLIDEAN_DISTANCE`, `NEGATIVE_INNER_PRODUCT`

#### Storage
```properties
app.storage.root=./data/uploads
app.reports.root=./data/reports
```

**Variabili:**
- `APP_STORAGE_ROOT` → Percorso salvataggio documenti caricati
- `APP_REPORTS_ROOT` → Percorso salvataggio report esportati

#### Multipart Upload
```properties
spring.servlet.multipart.max-file-size=50MB
spring.servlet.multipart.max-request-size=200MB
```

**Variabili:**
- `SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE` → Max size singolo file
- `SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE` → Max size totale request

#### Logging
```properties
logging.level.org.springframework.ai=INFO
logging.level.org.springframework.jdbc=INFO
logging.level.org.hibernate.SQL=DEBUG
```

**Livelli:** TRACE, DEBUG, INFO, WARN, ERROR

### Come Sovrascrivere Variabili

#### Metodo 1: Environment Variables (Sistema Operativo)
```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/reportai
export SPRING_AI_ANTHROPIC_API_KEY=sk-ant-...
java -jar reportAi.jar
```

#### Metodo 2: Parametri CLI
```bash
java -jar reportAi.jar \
  --spring.datasource.url=jdbc:postgresql://postgres:5432/reportai \
  --spring.ai.anthropic.api-key=sk-ant-...
```

#### Metodo 3: File application-[profile].properties
```bash
java -jar reportAi.jar --spring.profiles.active=production
# Legge: application-production.properties
```

#### Metodo 4: File .env (se usato con docker-compose)
```
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/reportai
SPRING_AI_ANTHROPIC_API_KEY=sk-ant-...
```

---

## 📥 Input Accepted / Validazioni

### ReportRequest - Generazione Report

| Campo | Tipo | Range/Validazione | Default | Descrizione |
|-------|------|-------------------|---------|-------------|
| `prompt` | String | ✅ Obbligatorio, max 2000 char | - | Istruzioni per report |
| `format` | String | JSON, CSV, XLSX, DOCX (case-insensitive) | JSON | Formato esportazione |
| `allowWebSearch` | Boolean | true/false | true | Abilita ricerca web |

**Validazione Prompt:**
- Non deve essere nullo o vuoto
- Consigliato: non oltre 2000 caratteri
- Supporta: caratteri speciali, accenti, lingue diverse

---

### DocumentUploadResponse - Upload

| Campo | Tipo | Validazione |
|-------|------|------------|
| `files` | List<MultipartFile> | ✅ Obbligatorio, minimo 1 file |
| File singolo | | Max 50MB per file, max 200MB totale request |

**Formati Supportati (Tika):**
- PDF, DOCX, XLS, XLSX, PPT, PPTX, ODP, ODT, RTF, TXT, HTML, XML, JSON, CSV, etc.

**Validazioni File:**
- Non vuoto (size > 0)
- SHA256 calcolato per deduplicazione
- Nomi file sanitizzati (caratteri speciali → underscore)

---

## 🔄 Flusso Completo: Esempio Pratico

### Scenario: Analisi Dati Vendite

#### Step 1: Upload Documenti
```bash
curl -X POST http://localhost:8080/api/documents/upload \
  -F "files=@vendite_q4.pdf" \
  -F "files=@clienti_principali.xlsx"
```

**Response:**
```json
[
  {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "originalFilename": "vendite_q4.pdf",
    "status": "INDEXED"
  },
  {
    "id": "550e8400-e29b-41d4-a716-446655440001",
    "originalFilename": "clienti_principali.xlsx",
    "status": "INDEXED"
  }
]
```

**Cosa Accade Internamente:**
1. File caricati in `./data/uploads/`
2. Testo estratto da PDF e XLSX
3. Suddiviso in chunk da 300 token
4. Embedding generati via Ollama
5. Vettori memorizzati in PostgreSQL

---

#### Step 2: Generazione Report
```bash
curl -X POST http://localhost:8080/api/reports/generate \
  -H "Content-Type: application/json" \
  -d '{
    "prompt": "Analizza le vendite nel Q4 2024. Quali sono i clienti top e le categorie più vendute? Quali tendenze noti?",
    "format": "XLSX",
    "allowWebSearch": false
  }'
```

**Processo Interno:**
1. **Ricerca vettoriale:** Embedding di "analizza le vendite..." cercato in PostgreSQL
2. **Recupero:** 5-10 chunk più rilevanti da PDF e XLSX
3. **Knowledge base:** `foundInKnowledgeBase = true` (documenti trovati)
4. **Web search:** Saltato (knowledge base sufficiente)
5. **Claude AI invocato con:**
   - System prompt: Istruzioni report professionale
   - User message: prompt + contesto documenti
6. **Generazione:** Risposta strutturata (sintesi, dettagli, conclusioni)
7. **Export XLSX:** Parsing Markdown table → Excel workbook
8. **Salvataggio:** File in `./data/reports/550e8400-...xlsx`

**Response:**
```json
{
  "status": "OK",
  "foundInKnowledgeBase": true,
  "webSearchUsed": false,
  "answer": "# Analisi Vendite Q4 2024\n\n## Sintesi\nIl Q4 2024 ha registrato crescita del 15% YoY...\n\n## Clienti Top\n| Cliente | Importo | % Totale |\n| Azienda A | € 500.000 | 35% |\n| Azienda B | € 300.000 | 20% |...",
  "fileName": "550e8400-e29b-41d4-a716-446655440000.xlsx",
  "downloadUrl": "/api/reports/download/550e8400-e29b-41d4-a716-446655440000.xlsx"
}
```

---

#### Step 3: Download Report
```bash
curl -O -J http://localhost:8080/api/reports/download/550e8400-e29b-41d4-a716-446655440000.xlsx
# File salvato: 550e8400-e29b-41d4-a716-446655440000.xlsx
```

---

## 🚀 Avvio e Esecuzione

### Prerequisiti
- Java 21+
- PostgreSQL (con estensione pgvector)
- Ollama (servizio embedding locale)
- API Key Anthropic (Claude)

### Build
```bash
mvn clean package
```

### Esecuzione Locale
```bash
java -jar target/reportAi-0.0.1-SNAPSHOT.jar
```

### Esecuzione con Profile
```bash
java -jar target/reportAi-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

### Port Predefinito
- App: `http://localhost:8080`
- PostgreSQL: `localhost:5400`
- Ollama: `http://localhost:11434`

---

## 📊 Struttura File

```
reportAi/
├── src/
│   ├── main/
│   │   ├── java/com/claude/reportAi/
│   │   │   ├── ReportAiApplication.java (entry point)
│   │   │   ├── configuration/
│   │   │   │   └── AiConfig.java (ChatClient Bean)
│   │   │   ├── controller/
│   │   │   │   ├── ReportController.java
│   │   │   │   ├── StoredFileController.java
│   │   │   │   └── GlobalExceptionHandler.java
│   │   │   ├── service/
│   │   │   │   ├── ReportOrchestratorService.java (core orchestration)
│   │   │   │   ├── ReportGenerationService.java (Claude)
│   │   │   │   ├── StoredFileService.java (ingestione)
│   │   │   │   ├── VectoreStoreService.java (ricerca KB)
│   │   │   │   ├── ReportExportService.java (export CSV/XLSX/DOCX)
│   │   │   │   └── WebSearchService.java (ricerca web)
│   │   │   ├── entity/
│   │   │   │   └── StoredFile.java
│   │   │   ├── repository/
│   │   │   │   ├── StoredFileRepository.java
│   │   │   │   └── ExcelTemplateRepository.java
│   │   │   └── dto/
│   │   │       ├── ReportRequest.java
│   │   │       ├── ReportResponse.java
│   │   │       ├── DocumentUploadResponse.java
│   │   │       └── ReportTableResponse.java
│   │   └── resources/
│   │       ├── application.properties (configurazione)
│   │       └── application-[profile].properties
│   └── test/
├── data/
│   ├── uploads/ (documenti caricati)
│   ├── reports/ (report esportati)
│   └── templates/
├── pom.xml (dipendenze Maven)
└── README.md (questa guida)
```

---

## 🔐 Sicurezza e Considerazioni

1. **API Key Claude:** Memorizzata in `application.properties`, preferibilmente non nel repo
2. **File Upload:** Sanitizzazione nomi, limitazione dimensioni, deduplicazione SHA256
3. **Accesso Download:** File endpoint accessibile pubblicamente → considera authentication/authorization
4. **Database:** Password PostgreSQL hardcoded → usare Secret Management (Vault, AWS Secrets, etc.)
5. **Rate Limiting:** Non implementato → considerare per produzione

---

## 🐛 Troubleshooting

### Nessun documento trovato
- Verificare: File uploadati con status "INDEXED"
- Verificare: Embedding generati (check PostgreSQL `vector_store` table)
- Verificare: Prompt simile al contenuto caricato

### Export fallisce
- Controllare: `./data/reports/` esiste e accessibile
- Controllare: Disco spazio disponibile
- Controllare: Permessi file system

### Claude API error
- Verificare: API Key valida e attiva
- Verificare: Quota API non esaurita
- Verificare: Connettività internet

---

## 📚 Dipendenze Principali

- **Spring Boot 3.5.6:** Framework applicativo
- **Spring AI 1.0.1:** Integrazione AI (Claude, Ollama, PGVector)
- **PostgreSQL Driver:** Connettività DB
- **Apache POI:** Export Excel (XLSX)
- **Apache Tika:** Estrazione testo documenti
- **Lombok:** Boilerplate code reduction
- **Jackson:** JSON processing

---

Ultima aggiornamento: Marzo 2026
