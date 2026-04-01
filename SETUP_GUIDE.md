# Setup Report-AI su Nuovo Computer

## Prerequisiti da Installare

### 1. **Docker Desktop**
- **Windows/macOS**: Scarica da https://www.docker.com/products/docker-desktop
- **Linux**: 
  ```bash
  curl -fsSL https://get.docker.com -o get-docker.sh
  sudo sh get-docker.sh
  ```
- Verifica l'installazione:
  ```bash
  docker --version
  docker compose --version
  ```

### 2. **Git** (per clonare il repository)
- **Windows**: https://git-scm.com/download/win
- **macOS**: `brew install git`
- **Linux**: `sudo apt-get install git`

### 3. **Ollama**
- **Windows/macOS**: Scarica da https://ollama.ai
- **Linux**: 
  ```bash
  curl -fsSL https://ollama.ai/install.sh | sh
  ```
- **Verifica**:
  ```bash
  ollama --version
  ```

---

## Step 1: Installare il Modello Embedding su Ollama

Prima di avviare l'applicazione, devi scaricare il modello di embedding su Ollama:

```bash
ollama pull mxbai-embed-large:latest
```

Questo comando scarica ~700MB. Può richiedere qualche minuto.

Verifica che Ollama sia in ascolto:
```bash
curl http://localhost:11434/api/tags
```

Dovresti vedere il modello `mxbai-embed-large` nella lista.

---

## Step 2: Clonare il Repository

```bash
git clone <URL-DEL-TUO-REPO> report-ai
cd report-ai
```

---

## Step 3: Configurare l'Indirizzo IP di Ollama

**IMPORTANTE**: Ollama deve essere accessibile dalla rete locale, non solo da `localhost`.

### Avviare Ollama su 0.0.0.0

#### **Windows (PowerShell come Admin):**
```powershell
$env:OLLAMA_HOST="0.0.0.0:11434"
ollama serve
```

#### **macOS:**
```bash
OLLAMA_HOST="0.0.0.0:11434" ollama serve
```

#### **Linux:**
```bash
OLLAMA_HOST="0.0.0.0:11434" ollama serve
```

**Verifica che sia raggiungibile** dal PC principale:
```bash
curl http://<IP-DEL-PC-OLLAMA>:11434/api/tags
```

---

## Step 4: Determinare l'IP Locale

Sostituisci `192.168.16.1` con l'IP corretto del PC dove Ollama è in esecuzione.

### **Windows:**
```powershell
ipconfig
```
Cerca "Indirizzo IPv4" nella sezione della tua connessione di rete.

### **macOS/Linux:**
```bash
ifconfig
```
o
```bash
hostname -I
```

---

## Step 5: Modificare docker-compose.yml

Apri `docker-compose.yml` e sostituisci `192.168.16.1` con l'IP corretto:

```yaml
spring-boot:
  environment:
    SPRING_AI_OLLAMA_BASE_URL: http://<IP-LOCALE>:11434
```

Se il PC dove gira l'app Docker e il PC dove gira Ollama sono lo stesso:
```yaml
SPRING_AI_OLLAMA_BASE_URL: http://host.docker.internal:11434
```

---

## Step 6: Avviare l'Applicazione

```bash
docker compose up -d
```

Attendi che i container siano pronti (~30-60 secondi):

```bash
docker compose ps
```

Dovresti vedere 3 container in stato "Up":
- `report-ai-db` (PostgreSQL)
- `report-ai-backend` (Spring Boot)
- `report-ai-frontend` (Nginx)

---

## Step 7: Accedere all'Applicazione

- **Frontend**: http://localhost
- **Backend API**: http://localhost:8080
- **Database**: localhost:5400 (utente: postgres, password: root)

---

## Verificare la Connessione a Ollama

Controlla i log del backend:

```bash
docker compose logs spring-boot --tail 50
```

Cerca messaggi simili a:
```
Initializing PGVectorStore schema
Successfully connected to Ollama
```

Se vedi errori tipo `UnknownHostException: 192.168.16.1`:
1. Verifica che Ollama sia in ascolto su `0.0.0.0` (non solo `localhost`)
2. Verifica l'IP nel `docker-compose.yml`
3. Esegui `docker compose restart spring-boot` dopo le modifiche

---

## Troubleshooting

### Ollama non raggiungibile
```bash
# Dal container spring-boot, verifica la connessione
docker compose exec spring-boot curl http://192.168.16.1:11434/api/tags
```

### Container crash
```bash
docker compose logs spring-boot
```

### Database già in uso
```bash
docker compose down -v  # Rimuove anche i volumi
docker compose up -d
```

### Porta 80 occupata
Modifica nel `docker-compose.yml`:
```yaml
frontend:
  ports:
    - "8000:80"  # Usa la porta 8000 invece di 80
```

---

## Script di Avvio Rapido (Salva come `start.sh` su Linux/macOS)

```bash
#!/bin/bash

# Avvia Ollama in background
echo "Avviando Ollama..."
OLLAMA_HOST="0.0.0.0:11434" ollama serve &
sleep 5

# Verifica il modello
echo "Verificando modello embedding..."
ollama pull mxbai-embed-large:latest

# Avvia Docker Compose
echo "Avviando applicazione..."
docker compose up -d

echo "✅ Applicazione avviata!"
echo "Accedi a http://localhost"
```

Rendi eseguibile:
```bash
chmod +x start.sh
./start.sh
```

---

## Script di Avvio Rapido (Windows PowerShell - Salva come `start.ps1`)

```powershell
# Esegui come Admin

Write-Host "Avviando Ollama..."
$env:OLLAMA_HOST = "0.0.0.0:11434"
Start-Process ollama -ArgumentList "serve" -NoNewWindow

Start-Sleep -Seconds 5

Write-Host "Verificando modello embedding..."
ollama pull mxbai-embed-large:latest

Write-Host "Avviando applicazione..."
docker compose up -d

Write-Host "✅ Applicazione avviata!"
Write-Host "Accedi a http://localhost"
```

---

## Architettura dell'Applicazione

```
PC Locale 1 (Ollama)
├── Ollama 11434
│   └── mxbai-embed-large:latest (embedding model)

PC Locale 2 / Docker (App)
├── PostgreSQL 5400
├── Spring Boot 8080
│   └── Connesso a Ollama 192.168.16.1:11434
└── Nginx 80
    └── React Frontend
```

---

## Credenziali di Default

| Servizio | Host | Porta | User | Password |
|----------|------|-------|------|----------|
| PostgreSQL | localhost | 5400 | postgres | root |
| Spring Boot API | localhost | 8080 | - | - |
| Frontend | localhost | 80 | - | - |
| Ollama | 192.168.16.1 | 11434 | - | - |

---

## Note Importanti

1. **Ollama deve essere sempre in ascolto su `0.0.0.0:11434`**, non solo `localhost`
2. **L'IP `192.168.16.1` va sostituito con il vero IP locale** del PC dove Ollama è in esecuzione
3. **Se Docker e Ollama sono sullo stesso PC**, usa `host.docker.internal:11434` (Windows/macOS) o l'IP locale (Linux)
4. **Firewall**: Assicurati che la porta 11434 non sia bloccata dal firewall

---

## Contatti e Supporto

Per problemi, controlla i log:
```bash
docker compose logs -f
```
