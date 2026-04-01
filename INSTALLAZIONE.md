# Guida all'installazione – Report AI

Questa guida spiega come installare e avviare l'applicazione **Report AI** su qualsiasi computer Windows, anche senza conoscenze tecniche specifiche.

---

## Cosa si installa sul computer

Serve **un solo programma**: **Docker Desktop**.

Docker è una tecnologia che permette di eseguire applicazioni in modo isolato, senza installare Java, database, Python o altri componenti direttamente sul computer. Tutto ciò che serve (backend, frontend, database, motore AI) viene avviato automaticamente all'interno di Docker.

---

## Struttura della cartella dell'applicazione

Prima di iniziare, assicurarsi che la cartella dell'applicazione contenga questi file e cartelle:

```
reportAi/
├── docker-compose.yml     ← file principale di configurazione Docker
├── nginx.conf             ← configurazione del server web
├── Dockerfile             ← istruzioni per costruire il backend
├── .env                   ← chiavi API (da creare – vedi Passo 3)
├── .env.example           ← modello per il file .env
├── data/
│   ├── logo.png           ← logo aziendale per i report
│   └── uploads/           ← cartella per i file caricati
├── frontend/
│   └── dist/              ← file compilati del sito web (forniti a parte)
└── src/                   ← codice sorgente del backend
```

---

## PASSO 1 – Installare Docker Desktop

1. Aprire il browser e andare su: **https://www.docker.com/products/docker-desktop**
2. Cliccare su **"Download for Windows"**
3. Avviare il file scaricato (`Docker Desktop Installer.exe`) e seguire l'installazione
4. Al termine, **riavviare il computer** se richiesto
5. Avviare **Docker Desktop** dall'icona sul desktop o dal menu Start
6. Attendere che Docker sia pronto: l'icona nella barra delle applicazioni (in basso a destra) diventerà verde con la scritta **"Docker Desktop is running"**

> **Nota:** Docker Desktop richiede Windows 10 versione 2004 o superiore (o Windows 11).

---

## PASSO 2 – Copiare i file del frontend

1. Prendere la cartella `dist` fornita insieme all'applicazione
2. Copiarla dentro la cartella `reportAi/frontend/` in modo da ottenere:
   ```
   reportAi/
   └── frontend/
       └── dist/
           ├── index.html
           └── assets/
               └── ...
   ```

---

## PASSO 3 – Configurare le chiavi API

Le chiavi API permettono all'applicazione di comunicare con i servizi di intelligenza artificiale.

1. Aprire la cartella `reportAi`
2. Trovare il file `.env.example`
3. **Copiarlo** nella stessa cartella e rinominarlo in **`.env`** (senza `.example`)
   - Su Windows: fare clic destro → Copia → incollare nella stessa cartella → rinominare
4. Aprire il file `.env` con il Blocco Note
5. Sostituire i valori segnaposto con le chiavi reali:
   ```
   ANTHROPIC_API_KEY=sk-ant-api03-la-tua-chiave-reale
   TAVILY_API_KEY=tvly-la-tua-chiave-reale
   ```
6. Salvare e chiudere il file

> **Dove trovo le chiavi?**
> - Chiave Anthropic: https://console.anthropic.com/ → sezione "API Keys"
> - Chiave Tavily: https://tavily.com/ → sezione "API Keys" dopo la registrazione

---

## PASSO 4 – Avviare l'applicazione

1. Aprire il **Prompt dei comandi** (cercare "cmd" nel menu Start) oppure **PowerShell**
2. Spostarsi nella cartella dell'applicazione con il comando:
   ```
   cd C:\percorso\dove\hai\messo\reportAi
   ```
   *(sostituire il percorso con quello reale sul tuo computer)*
3. Digitare il comando di avvio:
   ```
   docker compose up -d
   ```
4. Premere **Invio** e attendere

Docker scaricherà i componenti necessari e li avvierà. **Al primo avvio può volerci dai 5 ai 15 minuti** perché viene scaricato anche il modello di intelligenza artificiale (~700 MB). Gli avvii successivi saranno molto più rapidi (circa 1 minuto).

---

## PASSO 5 – Verificare che tutto funzioni

Dopo aver avviato l'applicazione, aprire il browser e andare su:

**http://localhost**

Se compare la pagina dell'applicazione, tutto funziona correttamente.

Per controllare lo stato dei componenti, digitare nel Prompt dei comandi:
```
docker compose ps
```

Si dovrebbe vedere una tabella simile a questa:

| Nome                    | Stato   | Porta          |
|-------------------------|---------|----------------|
| report-ai-db            | running | 5400           |
| report-ai-ollama        | running | 11434          |
| report-ai-backend       | running | 8080           |
| report-ai-frontend      | running | 80             |
| report-ai-ollama-init   | exited  | *(normale)*    |

> **Nota:** `report-ai-ollama-init` con stato "exited" è **normale** – è un componente che si avvia una sola volta per scaricare il modello AI e poi si ferma.

---

## Componenti dell'applicazione

| Componente | Descrizione |
|---|---|
| **PostgreSQL + pgvector** | Il database dove vengono salvati i dati e i prezzi interni aziendali (prezziari) |
| **Ollama** | Motore di intelligenza artificiale locale per l'analisi semantica dei documenti. Gira interamente sul tuo computer, nessun dato viene inviato fuori |
| **Backend (Spring Boot)** | Il cuore dell'applicazione: analizza i PDF, interroga i database di prezzi, genera i preventivi |
| **Frontend (Nginx)** | Il sito web che si apre nel browser |

---

## Comandi utili

Tutti i comandi vanno eseguiti nel Prompt dei comandi nella cartella `reportAi`.

| Operazione | Comando |
|---|---|
| Avviare l'applicazione | `docker compose up -d` |
| Fermare l'applicazione | `docker compose down` |
| Riavviare un componente | `docker compose restart spring-boot` |
| Vedere i log in tempo reale | `docker compose logs -f spring-boot` |
| Controllare lo stato | `docker compose ps` |
| Aggiornare l'applicazione | `docker compose down` poi `docker compose up -d --build` |

---

## Risoluzione problemi

**La pagina non si apre nel browser**
- Verificare che Docker Desktop sia avviato (icona verde in basso a destra)
- Attendere ancora qualche minuto: al primo avvio i componenti impiegano del tempo
- Controllare i log: `docker compose logs spring-boot`

**Il backend dà errore di connessione al database**
- Aspettare 1-2 minuti: il database potrebbe ancora stare avviandosi
- Controllare: `docker compose ps` → verificare che `report-ai-db` sia "running"

**Il modello AI non si scarica (ollama-init in errore)**
- Verificare la connessione internet
- Rieseguire manualmente il download:
  ```
  docker compose run --rm ollama-init
  ```

**Aggiornamento dell'applicazione**
Quando viene fornita una nuova versione:
```
docker compose down
docker compose up -d --build
```
I dati nel database vengono conservati automaticamente.

---

## Note importanti

- **I dati sono al sicuro**: anche fermando l'applicazione con `docker compose down`, tutti i dati nel database rimangono salvati. Per eliminarli definitivamente bisogna usare `docker compose down -v` (sconsigliato se non si vuole perdere i dati).
- **Requisiti minimi consigliati**: 16 GB di RAM, 20 GB di spazio libero su disco, connessione internet per il primo avvio.
- **Il computer deve rimanere acceso** mentre si usa l'applicazione (non va in sospensione).
