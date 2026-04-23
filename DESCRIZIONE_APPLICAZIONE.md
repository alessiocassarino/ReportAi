# ReportAI — Descrizione dell'Applicazione

> Piattaforma intelligente per l'analisi documentale e la gestione delle offerte nel settore Oil & Gas / EPC

---

## Cos'è ReportAI

**ReportAI** è una piattaforma web che automatizza alcune delle attività più critiche e time-consuming per le aziende che operano nel settore Oil & Gas ed EPC (Engineering, Procurement & Construction):

- Analisi del rischio nei contratti commerciali
- Generazione di preventivi per progetti di pipeline e infrastrutture
- Confronto e valutazione delle offerte dei fornitori
- Gestione di una base documentale con ricerca intelligente

L'applicazione utilizza modelli di intelligenza artificiale avanzati — tra cui **Claude di Anthropic** e **Gemini di Google** — per elaborare documenti tecnici e commerciali, estrarne le informazioni rilevanti e produrre report professionali in formato Word (`.docx`) pronti da presentare al management o al cliente.

---

## A Chi è Rivolto

| Profilo | Utilizzo Principale |
|---|---|
| **Contract Manager** | Analisi rapida del rischio nei contratti in fase di negoziazione |
| **Cost Estimator / QS** | Generazione di preventivi preliminari su specifiche tecniche |
| **Procurement Manager** | Confronto strutturato tra offerte di più fornitori |
| **Management / Direzione** | Report executive chiari e sintetici per le decisioni strategiche |

---

## Funzionalità Principali

### 1. Analisi Rischi Contrattuali

**Cosa fa**: carica un contratto in formato PDF e ricevi in pochi minuti un report dettagliato di tutti i rischi identificati per il Contractor, con il livello di criticità e le raccomandazioni di negoziazione.

**Come funziona**:
1. L'utente carica il PDF del contratto e seleziona il modello AI da utilizzare
2. Il sistema estrae automaticamente il testo e lo divide per sezioni (termini di pagamento, penali, garanzie, ecc.)
3. Ogni sezione viene analizzata dall'intelligenza artificiale dal punto di vista del Contractor
4. I risultati vengono aggregati in un report executive in formato Word

**Cosa contiene il report**:
- **Valutazione complessiva** del contratto: Favorevole / Equilibrato / Sfavorevole
- **Raccomandazione finale**: Firmare / Negoziare / Rifiutare
- **Executive Summary** per il board (3–5 frasi)
- **Top 5 clausole critiche** con testo attuale e testo alternativo proposto
- **Matrice dei rischi** ordinata per livello (Alto / Medio / Basso)
- **Analisi per sezione** con rischi specifici e clausole mancanti
- **Clausole mancanti** che sarebbe necessario inserire

---

### 2. Generazione Preventivi EPC

**Cosa fa**: a partire da una specifica tecnica di progetto (pipeline, stazioni di pompaggio, infrastrutture), genera un preventivo dettagliato con stime di costo per categoria.

**Come funziona**:
1. L'utente carica il documento di specifica tecnica del progetto
2. Il sistema estrae automaticamente le caratteristiche principali (tipo di pipeline, diametro, terreno, materiali)
3. L'AI elabora i dati combinando:
   - I dati storici e i listini interni aziendali (caricati come base documentale)
   - I benchmark di mercato aggiornati (ricercati automaticamente sul web)
4. Viene generato un preventivo strutturato in formato Word

**Cosa contiene il report**:
- Sintesi del progetto e scope of work
- Bill of Materials (BoM) con quantità e costi unitari
- Costi suddivisi per categoria (materiali, installazione, subappalti, EPCM)
- Cronoprogramma preliminare
- Benchmark di riferimento utilizzati
- Note e assunzioni alla base delle stime

**Valute**: tutte le stime sono espresse in **Euro (EUR)**, con conversioni automatiche dal Dollaro USD al cambio del giorno.

---

### 3. Confronto Offerte Fornitori

**Cosa fa**: carica le offerte commerciali di due o più fornitori e ottieni una valutazione comparativa strutturata con la raccomandazione sul fornitore da preferire.

**Come funziona**:
1. L'utente carica i PDF delle offerte ricevute (almeno 2)
2. Il sistema estrae da ciascuna offerta: prezzi, termini di consegna, condizioni di pagamento, qualifiche tecniche, rischi contrattuali
3. L'AI esegue un confronto strutturato e produce un report con la valutazione finale

**Cosa contiene il report**:
- Tabella comparativa di tutti i fornitori
- Fornitore consigliato con motivazione
- Pro e contro di ciascuna offerta
- Analisi dei rischi per fornitore
- Raccomandazioni per la negoziazione

---

### 4. Base Documentale con Ricerca Intelligente (RAG)

**Cosa fa**: carica i documenti aziendali (listini prezzi, contratti tipo, specifiche tecniche, dati storici di progetto) e rendili disponibili come fonte di conoscenza per i modelli AI durante le analisi e le stime.

**Come funziona**:
1. L'utente carica uno o più documenti (PDF, Word, Excel, CSV, TXT)
2. Il sistema li indicizza automaticamente in un database vettoriale
3. Quando l'AI analizza un contratto o genera un preventivo, attinge automaticamente alla base documentale per produrre risposte più accurate e coerenti con i dati aziendali

**Formati supportati**:
- PDF (`.pdf`)
- Word (`.docx`)
- Excel (`.xlsx`)
- Testo (`.txt`, `.csv`, `.rtf`, `.html`)
- Dimensione massima per file: **50 MB**

---

## Modelli AI Disponibili

L'utente può scegliere quale modello di intelligenza artificiale utilizzare per ogni analisi, in base al bilanciamento desiderato tra velocità, qualità e costo.

### Modelli Anthropic (Claude)

| Modello | Caratteristiche | Uso consigliato |
|---|---|---|
| **Claude Haiku 4.5** | Veloce ed economico | Analisi preliminari, test |
| **Claude Sonnet 4.6** | Ottimo equilibrio qualità/velocità | Uso quotidiano |
| **Claude Opus 4.7** | Massima qualità | Contratti complessi, preventivi strategici |

### Modelli Google (Gemini)

| Modello | Caratteristiche | Uso consigliato |
|---|---|---|
| **Gemini 2.5 Flash** | Veloce e bilanciato | Uso quotidiano |
| **Gemini 2.5 Pro** | Massima qualità Google | Analisi approfondite |
| **Gemini 2.5 Flash Lite** | Leggero e veloce | Uso intensivo ed economico |

---

## Gestione Utenti e Accessi

L'applicazione prevede un sistema di accesso basato su ruoli, così da garantire che ogni utente abbia visibilità solo sulle funzionalità di sua competenza.

| Ruolo | Permessi |
|---|---|
| **Amministratore** | Accesso completo a tutte le funzionalità + gestione utenti |
| **Analista** | Accesso a tutte le funzionalità di analisi |
| **Utente** | Accesso alle funzionalità di analisi (senza gestione utenti) |

### Sicurezza degli Accessi
- Login con email e password
- Sessione con token sicuro (scade automaticamente dopo 15 minuti di inattività)
- Possibilità di disconnettersi da tutti i dispositivi contemporaneamente
- Password cifrate e mai memorizzate in chiaro

---

## Output: I Report Word

Tutti i servizi producono report in formato **Microsoft Word (`.docx`)**, scaricabili direttamente dall'interfaccia web. I report includono:

- Logo e intestazione aziendale
- Struttura professionale con titoli, tabelle e sezioni
- Contenuto generato dall'AI in italiano
- Pronti per essere condivisi, modificati o stampati

---

## Processo Tipico di Utilizzo

```
1. Login nell'applicazione
       │
       ▼
2. Selezione del servizio
   (Analisi Contratto / Preventivo / Confronto Offerte / Documenti)
       │
       ▼
3. Caricamento del documento PDF
       │
       ▼
4. Scelta del modello AI e avvio elaborazione
       │
       ▼
5. Attesa dell'elaborazione (da pochi secondi a qualche minuto)
   — La barra di avanzamento mostra lo stato in tempo reale —
       │
       ▼
6. Download del report Word (.docx)
```

---

## Tempi di Elaborazione Indicativi

| Servizio | Documento Semplice | Documento Complesso |
|---|---|---|
| Analisi Contratto | 2–5 minuti | 10–20 minuti |
| Generazione Preventivo | 3–8 minuti | 10–20 minuti |
| Confronto Offerte (2 fornitori) | 3–6 minuti | 8–15 minuti |
| Indicizzazione Documento (RAG) | < 1 minuto | 2–5 minuti |

> I tempi dipendono dalla lunghezza del documento, dal modello AI selezionato e dal carico dei servizi cloud.

---

## Tecnologie Utilizzate

| Componente | Tecnologia |
|---|---|
| Backend | Java 21 — Spring Boot 3.5 |
| Frontend | React (interfaccia web) |
| Database | PostgreSQL con estensione vettoriale (pgvector) |
| AI Principale | Anthropic Claude API |
| AI Alternativa | Google Cloud Vertex AI (Gemini) |
| Ricerca Web | Tavily Search API |
| Embedding Documenti | Ollama — modello mxbai-embed-large |
| Report | Apache POI (formato Word .docx) |
| Deployment | Docker + Nginx |

---

## Disponibilità e Accesso

L'applicazione è accessibile tramite **browser web** (Chrome, Firefox, Edge, Safari) da qualsiasi computer connesso alla rete aziendale, senza necessità di installare software aggiuntivo sul computer dell'utente.

**URL di accesso**: definito in fase di installazione (es. `http://192.168.1.100` oppure `https://reportai.azienda.it`)

---

*ReportAI — Piattaforma di Analisi Documentale per il settore Oil & Gas / EPC*
*Documento descrittivo — versione 1.0*
