# Guida all'Installazione — ReportAI

> Documento tecnico per la configurazione dell'applicazione su un nuovo server o computer.

---

## Indice

1. [Prerequisiti Software](#1-prerequisiti-software)
2. [Servizi di Terze Parti](#2-servizi-di-terze-parti)
3. [Preparazione del Progetto](#3-preparazione-del-progetto)
4. [Configurazione delle Variabili d'Ambiente](#4-configurazione-delle-variabili-dambiente)
5. [Build e Avvio dell'Applicazione](#5-build-e-avvio-dellapplicazione)
6. [Verifica del Funzionamento](#6-verifica-del-funzionamento)
7. [Requisiti Hardware Consigliati](#7-requisiti-hardware-consigliati)
8. [Note per la Produzione](#8-note-per-la-produzione)

---

## 1. Prerequisiti Software

Installare i seguenti strumenti sul nuovo computer **prima** di procedere.

### 1.1 Docker Desktop

Docker è il requisito principale: gestisce automaticamente tutti i componenti dell'applicazione (database, backend, frontend).

- **Download**: [https://www.docker.com/products/docker-desktop](https://www.docker.com/products/docker-desktop)
- **Versione minima richiesta**: Docker Desktop 4.x con Docker Compose V2
- **Sistemi operativi supportati**:
  - Windows 10/11 (64-bit) con WSL2 abilitato
  - macOS 12 Monterey o superiore
  - Linux (Ubuntu 20.04+, Debian 11+, RHEL 8+)

> **Windows**: dopo l'installazione di Docker Desktop, assicurarsi che WSL2 sia abilitato. Docker Desktop guida automaticamente l'utente in questa configurazione al primo avvio.

Verifica installazione:
```bash
docker --version
docker compose version
```

### 1.2 Git

Necessario per scaricare il codice sorgente del progetto.

- **Download**: [https://git-scm.com/downloads](https://git-scm.com/downloads)
- **Versione minima**: 2.x

Verifica installazione:
```bash
git --version
```

### 1.3 Ollama

Ollama è il motore locale per i modelli di embedding (indicizzazione documenti). Deve essere installato direttamente sul computer host, **non** dentro Docker.

- **Download**: [https://ollama.com/download](https://ollama.com/download)
- **Versione minima**: 0.4.x

Dopo l'installazione, scaricare il modello di embedding usato dall'applicazione:
```bash
ollama pull mxbai-embed-large
```

> Il download del modello richiede circa **670 MB** di spazio su disco e qualche minuto a seconda della velocità della connessione.

Verifica installazione:
```bash
ollama list
# Deve comparire: mxbai-embed-large
```

### 1.4 Riepilogo Prerequisiti

| Software | Versione Minima | Obbligatorio |
|---|---|---|
| Docker Desktop | 4.x | ✅ Sì |
| Docker Compose V2 | 2.x | ✅ Sì (incluso in Docker Desktop) |
| Git | 2.x | ✅ Sì |
| Ollama + mxbai-embed-large | 0.4.x | ✅ Sì |

---

## 2. Servizi di Terze Parti

L'applicazione si appoggia a tre servizi cloud esterni. È necessario disporre delle relative chiavi API prima di avviare l'installazione.

### 2.1 Anthropic Claude API

Motore AI principale per l'analisi contratti, la generazione preventivi e il confronto offerte.

- **Sito**: [https://console.anthropic.com](https://console.anthropic.com)
- **Cosa fare**:
  1. Creare un account su `console.anthropic.com`
  2. Andare in **API Keys** → **Create Key**
  3. Copiare la chiave (formato: `sk-ant-api03-...`)
- **Piano consigliato**: piano a consumo (pay-as-you-go) — si paga in base all'utilizzo
- **Modelli utilizzati dall'app**:
  - `claude-haiku-4-5` — veloce, economico (default)
  - `claude-sonnet-4-5` / `claude-sonnet-4-6` — bilanciato
  - `claude-opus-4-6` / `claude-opus-4-7` — massima qualità

> **Importante**: la chiave API Anthropic è strettamente confidenziale. Non condividerla e non inserirla in file versionati con Git.

### 2.2 Google Cloud Vertex AI (Gemini)

Motore AI alternativo di Google, utilizzabile come opzione aggiuntiva ai modelli Claude.

- **Sito**: [https://console.cloud.google.com](https://console.cloud.google.com)
- **Cosa fare**:
  1. Creare o accedere a un progetto Google Cloud
  2. Abilitare le API: **Vertex AI API** e **Cloud Storage API**
  3. Creare un **Service Account** con il ruolo `Vertex AI User`
  4. Generare una chiave JSON per il service account e scaricarla
  5. Rinominare il file JSON in un nome descrittivo (es. `gcp-credentials.json`)
  6. Copiare il file nella cartella: `src/main/resources/config/`
  7. Aggiornare il percorso nel file di configurazione (vedi sezione 4)
- **Modelli utilizzati dall'app**:
  - `gemini-2.5-flash` — veloce e bilanciato
  - `gemini-2.5-pro` — massima qualità
  - `gemini-2.5-flash-lite` — economico e leggero

> **Nota**: i modelli Google Gemini sono opzionali. Se si desidera usare solo i modelli Claude di Anthropic, questa configurazione può essere saltata (l'app funzionerà ugualmente).

### 2.3 Tavily Web Search API

Servizio di ricerca web usato durante la generazione dei preventivi per recuperare prezzi e benchmark di mercato aggiornati.

- **Sito**: [https://tavily.com](https://tavily.com)
- **Cosa fare**:
  1. Creare un account su `tavily.com`
  2. Accedere alla dashboard → copiare la chiave API (formato: `tvly-...`)
- **Piano consigliato**: piano gratuito per test, piano Pro per utilizzo intensivo

### 2.4 Riepilogo Servizi di Terze Parti

| Servizio | Obbligatorio | Utilizzo |
|---|---|---|
| Anthropic Claude API | ✅ Sì | Analisi AI principale |
| Google Cloud Vertex AI | ⚠️ Opzionale | Modelli Gemini alternativi |
| Tavily Web Search | ✅ Sì (per i preventivi) | Prezzi e benchmark di mercato |

---

## 3. Preparazione del Progetto

### 3.1 Clonare il Repository

```bash
git clone <URL_DEL_REPOSITORY> reportAi
cd reportAi
```

### 3.2 Struttura delle Cartelle Principale

```
reportAi/
├── src/                          # Codice sorgente backend (Java)
│   └── main/resources/
│       ├── application.properties
│       └── config/               # ← inserire qui il file JSON di Google Cloud
├── frontend/
│   └── dist/                     # Frontend compilato (già pronto)
├── data/
│   ├── logo.png                  # Logo aziendale per i report
│   └── uploads/                  # File caricati dagli utenti (creato automaticamente)
├── docker-compose.yml
├── Dockerfile
├── nginx.conf
└── .env                          # ← da creare (vedi sezione 4)
```

### 3.3 Inserire le Credenziali Google Cloud

Copiare il file JSON del service account Google Cloud nella cartella:
```
src/main/resources/config/
```

Aggiornare il percorso in `src/main/resources/application.properties` alla riga:
```properties
spring.ai.vertex.ai.gemini.credentials.location=classpath:config/NOME_DEL_TUO_FILE.json
```

Aggiornare anche il project-id Google Cloud se diverso:
```properties
spring.ai.vertex.ai.gemini.project-id=ID_DEL_TUO_PROGETTO_GCP
```

### 3.4 Inserire il Logo Aziendale

Sostituire il file `data/logo.png` con il logo aziendale desiderato.
- **Formato**: PNG
- **Dimensioni consigliate**: 300 × 100 pixel (orizzontale)
- Il logo viene inserito automaticamente in tutti i report DOCX generati.

---

## 4. Configurazione delle Variabili d'Ambiente

Creare un file `.env` nella **cartella radice del progetto** (stessa posizione di `docker-compose.yml`).

```bash
# Crea il file .env
touch .env   # Linux/Mac
# oppure creare manualmente il file .env su Windows
```

Contenuto del file `.env`:
```env
# Chiave API Anthropic (obbligatoria)
ANTHROPIC_API_KEY=sk-ant-api03-LA_TUA_CHIAVE_QUI

# Chiave API Tavily (obbligatoria per i preventivi)
TAVILY_API_KEY=tvly-LA_TUA_CHIAVE_QUI

# Sicurezza JWT - cambiare con una stringa casuale di almeno 32 caratteri
JWT_SECRET=cambia-questa-stringa-con-una-casuale-di-almeno-32-caratteri

# (Opzionale) Password database - cambiare in produzione
DB_PASSWORD=root
```

> **Importante**: non versionare mai il file `.env` con Git. Verificare che `.env` sia presente nel file `.gitignore`.

---

## 5. Build e Avvio dell'Applicazione

### 5.1 Avvio Completo (Prima Installazione)

```bash
# Dalla cartella radice del progetto
docker compose up --build -d
```

Questo comando esegue automaticamente:
1. **Build del backend** Java (Maven compila il codice sorgente, ~3-5 minuti)
2. **Avvio di PostgreSQL** con estensione pgvector
3. **Avvio di Ollama** e download del modello di embedding (se non già presente)
4. **Avvio del backend** Spring Boot (applica automaticamente le migrazioni del database)
5. **Avvio del frontend** Nginx con il sito web compilato

### 5.2 Ordine di Avvio dei Servizi

Docker Compose gestisce automaticamente le dipendenze tra i servizi:

```
postgres (healthy)
    └──> ollama (healthy)
             └──> ollama-init (scarica modello embedding)
             └──> spring-boot (avvia il backend)
                      └──> frontend (nginx, serve il sito)
```

### 5.3 Monitoraggio dei Log

```bash
# Tutti i log
docker compose logs -f

# Solo il backend
docker compose logs -f spring-boot

# Solo il database
docker compose logs -f postgres
```

L'applicazione è pronta quando nei log del backend appare:
```
Started ReportAiApplication in X.XXX seconds
```

### 5.4 Avvii Successivi

Per i riavvii successivi (senza rebuild):
```bash
docker compose up -d
```

Per fermare l'applicazione:
```bash
docker compose down
```

Per fermare e rimuovere tutti i dati (reset completo):
```bash
docker compose down -v
```

---

## 6. Verifica del Funzionamento

### 6.1 Accesso all'Applicazione

Aprire il browser e navigare a:
```
http://localhost
```

### 6.2 Credenziali Iniziali

Al primo avvio, il sistema crea automaticamente un utente amministratore:

| Campo | Valore |
|---|---|
| Email | `admin@nexoniq.com` |
| Password | `Admin1234!` |

> **Cambiare la password immediatamente** dopo il primo accesso.

### 6.3 Verifica dei Servizi

| URL | Descrizione | Risposta attesa |
|---|---|---|
| `http://localhost` | Interfaccia web | Pagina di login |
| `http://localhost/api/auth/health` | Health check backend | `{"status":"UP"}` |
| `localhost:5400` | Database PostgreSQL | Connessione accettata |

### 6.4 Verifica Ollama

```bash
# Verificare che Ollama risponda
curl http://localhost:11434/api/tags

# Deve comparire mxbai-embed-large nell'elenco
```

---

## 7. Requisiti Hardware Consigliati

### 7.1 Configurazione Minima (Sviluppo / Test)

| Componente | Requisito |
|---|---|
| CPU | 4 core |
| RAM | 16 GB |
| Disco | 50 GB SSD liberi |
| Connessione | Internet stabile |

### 7.2 Configurazione Consigliata (Produzione)

| Componente | Requisito |
|---|---|
| CPU | 8–16 core |
| RAM | 32 GB |
| Disco | 100 GB SSD (+ backup separato) |
| Connessione | Internet stabile, larghezza di banda adeguata |

### 7.3 Dettaglio Consumo Risorse

| Servizio | RAM | Disco |
|---|---|---|
| PostgreSQL + pgvector | 2–4 GB | 10–50 GB (dipende dai documenti) |
| Ollama + modello embedding | 4–6 GB | ~1 GB per il modello |
| Backend Spring Boot | 2–3 GB | – |
| Frontend Nginx | < 100 MB | – |

---

## 8. Note per la Produzione

### 8.1 Sicurezza

- **Cambiare la password admin** al primo accesso
- **Usare chiavi API dedicate** per ogni ambiente (dev, staging, prod)
- **Non esporre la porta 5400** (PostgreSQL) su Internet — è solo per accesso locale
- **Cambiare la password del database** (`DB_PASSWORD` nel file `.env`)
- **Cambiare il secret JWT** con una stringa casuale e sicura (almeno 64 caratteri)

### 8.2 Backup

Eseguire backup periodici del volume PostgreSQL:
```bash
docker exec reportai-postgres pg_dump -U postgres vector_db > backup_$(date +%Y%m%d).sql
```

### 8.3 Aggiornamenti

Per aggiornare l'applicazione a una nuova versione:
```bash
git pull origin main
docker compose up --build -d
```

### 8.4 Porte Utilizzate

| Porta | Servizio | Visibilità |
|---|---|---|
| 80 | Frontend (Nginx) | Pubblica |
| 8080 | Backend Spring Boot | Solo interna (Docker) |
| 5400 | PostgreSQL | Solo locale |
| 11434 | Ollama | Solo locale |

---

*Documento tecnico — ReportAI v0.0.1*
