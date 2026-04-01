# Setup Report-AI su Nuovo Computer (PowerShell)

## Prerequisiti da Installare

### 1. **Docker Desktop**
- Scarica da https://www.docker.com/products/docker-desktop
- Installa e riavvia il PC
- Verifica nel PowerShell (come Admin):
  ```powershell
  docker --version
  docker compose version
  ```

### 2. **Git**
- Scarica da https://git-scm.com/download/win
- Installa con opzioni di default

### 3. **Ollama**
- Scarica da https://ollama.ai
- Installa e avvia

---

## Setup Rapido (PowerShell come Admin)

```powershell
# 1. Scarica il modello di embedding
ollama pull mxbai-embed-large:latest

# 2. Determina il tuo IP locale
ipconfig

# 3. Avvia Ollama in ascolto sulla rete locale
$env:OLLAMA_HOST = "0.0.0.0:11434"
ollama serve
```

In un **nuovo PowerShell** (mentre Ollama è in esecuzione):

```powershell
# 4. Clona il repository
git clone <URL-DEL-REPO> report-ai
cd report-ai

# 5. Modifica docker-compose.yml con l'IP corretto
# Apri il file e sostituisci 192.168.16.1 con il tuo IP locale
# (ad esempio: 192.168.1.100, 10.0.0.50, ecc.)

# 6. Avvia i container
docker compose up -d

# 7. Accedi all'app
# Frontend:  http://localhost
# Backend:   http://localhost:8080
```

---

## Script Automatico (Salva come `setup.ps1`)

Esegui come Admin:

```powershell
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser

# Esegui lo script
.\setup.ps1 -OllamaIP "192.168.16.1"
```

### Contenuto dello script:

```powershell
param(
    [Parameter(Mandatory=$true)]
    [string]$OllamaIP
)

$OllamaPort = 11434

Write-Host "=========================================="
Write-Host "  Setup Report-AI"
Write-Host "=========================================="
Write-Host "IP Ollama: $OllamaIP:$OllamaPort"
Write-Host ""

# Verifica Docker
Write-Host "Verificando Docker..."
$docker = docker --version
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Docker non trovato. Installa Docker Desktop."
    exit 1
}
Write-Host "✓ $docker"

# Verifica Docker Compose
Write-Host "Verificando Docker Compose..."
$compose = docker compose version
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Docker Compose non trovato."
    exit 1
}
Write-Host "✓ $compose"

# Verifica Ollama
Write-Host "Verificando Ollama..."
$ollama = ollama --version
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Ollama non trovato. Installa Ollama da https://ollama.ai"
    exit 1
}
Write-Host "✓ $ollama"

# Testa connessione a Ollama
Write-Host "Verificando connessione a Ollama ($OllamaIP:$OllamaPort)..."
try {
    $response = Invoke-WebRequest -Uri "http://$OllamaIP:$OllamaPort/api/tags" -ErrorAction Stop
    Write-Host "✓ Ollama raggiungibile"
} catch {
    Write-Host "❌ Impossibile raggiungere Ollama su $OllamaIP:$OllamaPort"
    Write-Host "   Assicurati che Ollama sia in ascolto su 0.0.0.0:11434"
    Write-Host "   Esegui in PowerShell: `$env:OLLAMA_HOST = '0.0.0.0:11434'; ollama serve"
    exit 1
}

# Scarica modello
Write-Host "Verificando modello mxbai-embed-large..."
$tags = Invoke-WebRequest -Uri "http://$OllamaIP:$OllamaPort/api/tags" | ConvertFrom-Json
if ($tags.models.name -notcontains "mxbai-embed-large:latest") {
    Write-Host "Scaricando modello..."
    ollama pull mxbai-embed-large:latest
}
Write-Host "✓ Modello pronto"

# Aggiorna docker-compose.yml
Write-Host "Configurando docker-compose.yml..."
(Get-Content docker-compose.yml) -replace "SPRING_AI_OLLAMA_BASE_URL:.*", "SPRING_AI_OLLAMA_BASE_URL: http://$OllamaIP:11434" | Set-Content docker-compose.yml
Write-Host "✓ Configurazione aggiornata"

# Avvia container
Write-Host "Avviando container Docker..."
docker compose down --remove-orphans 2>$null
docker compose up -d

Write-Host "⏳ Attendendo 10 secondi..."
Start-Sleep -Seconds 10

Write-Host "✓ Stato container:"
docker compose ps

Write-Host ""
Write-Host "=========================================="
Write-Host "✅ Setup Completato!"
Write-Host "=========================================="
Write-Host ""
Write-Host "Accedi all'applicazione:"
Write-Host "🌐 Frontend:  http://localhost"
Write-Host "📡 Backend:   http://localhost:8080"
Write-Host "🗄️  Database:  localhost:5400 (user: postgres, pass: root)"
Write-Host ""
Write-Host "Verifica i log:"
Write-Host "docker compose logs -f spring-boot"
```

---

## Checklist di Setup

- [ ] Docker Desktop installato e funzionante
- [ ] Git installato
- [ ] Ollama installato
- [ ] Modello `mxbai-embed-large:latest` scaricato
- [ ] Ollama in ascolto su `0.0.0.0:11434`
- [ ] Repository clonato
- [ ] `docker-compose.yml` modificato con IP corretto
- [ ] Container Docker avviati (`docker compose up -d`)
- [ ] Frontend raggiungibile su http://localhost

---

## Risoluzione Problemi

### Ollama non raggiungibile dal container
```powershell
# Avvia Ollama in ascolto sulla rete locale
$env:OLLAMA_HOST = "0.0.0.0:11434"
ollama serve
```

### Determinare l'IP locale corretto
```powershell
ipconfig

# Cerca "Indirizzo IPv4" in una riga simile a:
# Ethernet adapter Ethernet:
#    Indirizzo IPv4. . . . . . . . . . . . : 192.168.x.x
```

### Container crash
```powershell
docker compose logs spring-boot --tail 50
```

### Riavviare tutto
```powershell
docker compose down -v
docker compose up -d
```

---

## Struttura del Progetto

```
report-ai/
├── Dockerfile                    # Build Spring Boot
├── docker-compose.yml            # Orquestrazione (PostgreSQL, Spring Boot, Nginx)
├── nginx.conf                    # Configurazione Nginx
├── pom.xml                       # Dipendenze Maven
├── src/                          # Codice sorgente
├── SETUP_GUIDE.md               # Guida setup completa
└── setup.ps1                    # Script setup Windows
```

---

## Architettura Finale

```
PC con Ollama (192.168.16.1)
├── Ollama 11434
│   └── mxbai-embed-large:latest

PC con Docker
├── PostgreSQL (5400)
├── Spring Boot (8080) → connesso a Ollama 192.168.16.1:11434
└── Nginx (80) → React Frontend
```

---

## Prossimi Step

1. Accedi a http://localhost
2. Upload di un documento PDF
3. Verifica che l'embedding funzioni correttamente
4. Controlla i log: `docker compose logs -f`
