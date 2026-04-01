#!/bin/bash

# Script di Setup Automatico per Report-AI
# Uso: ./setup.sh <IP_OLLAMA>
# Esempio: ./setup.sh 192.168.16.1

set -e

if [ $# -eq 0 ]; then
    echo "❌ Errore: IP di Ollama non specificato"
    echo "Uso: ./setup.sh <IP_OLLAMA>"
    echo "Esempio: ./setup.sh 192.168.16.1"
    exit 1
fi

OLLAMA_IP=$1
OLLAMA_PORT=11434

echo "=========================================="
echo "  Setup Report-AI"
echo "=========================================="
echo "IP Ollama: $OLLAMA_IP:$OLLAMA_PORT"
echo ""

# Verifica Docker
echo "✓ Verificando Docker..."
if ! command -v docker &> /dev/null; then
    echo "❌ Docker non trovato. Installa Docker Desktop."
    exit 1
fi
echo "  Docker versione: $(docker --version)"

# Verifica Docker Compose
echo "✓ Verificando Docker Compose..."
if ! docker compose version &> /dev/null; then
    echo "❌ Docker Compose non trovato."
    exit 1
fi
echo "  Docker Compose versione: $(docker compose version --short)"

# Verifica Ollama
echo "✓ Verificando Ollama..."
if ! command -v ollama &> /dev/null; then
    echo "❌ Ollama non trovato. Installa Ollama da https://ollama.ai"
    exit 1
fi
echo "  Ollama versione: $(ollama --version)"

# Testa connessione a Ollama
echo "✓ Verificando connessione a Ollama ($OLLAMA_IP:$OLLAMA_PORT)..."
if ! curl -s http://$OLLAMA_IP:$OLLAMA_PORT/api/tags > /dev/null 2>&1; then
    echo "⚠ Impossibile raggiungere Ollama su $OLLAMA_IP:$OLLAMA_PORT"
    echo "  Assicurati che Ollama sia in ascolto su 0.0.0.0:11434"
    echo "  Esegui: OLLAMA_HOST=0.0.0.0:11434 ollama serve"
    exit 1
fi
echo "  Ollama raggiungibile ✓"

# Verifica/scarica modello
echo "✓ Verificando modello mxbai-embed-large..."
if ! curl -s http://$OLLAMA_IP:$OLLAMA_PORT/api/tags | grep -q "mxbai-embed-large"; then
    echo "  Modello non trovato, scaricamento in corso..."
    ollama pull mxbai-embed-large:latest
fi
echo "  Modello pronto ✓"

# Aggiorna docker-compose.yml
echo "✓ Configurando docker-compose.yml..."
sed -i "s|SPRING_AI_OLLAMA_BASE_URL:.*|SPRING_AI_OLLAMA_BASE_URL: http://$OLLAMA_IP:11434|g" docker-compose.yml
echo "  Configurazione aggiornata ✓"

# Avvia l'applicazione
echo "✓ Avviando container Docker..."
docker compose down --remove-orphans 2>/dev/null || true
docker compose up -d

# Attendi che i container siano pronti
echo "⏳ Attendendo che i container siano pronti..."
sleep 10

# Verifica stato container
echo "✓ Stato container:"
docker compose ps

echo ""
echo "=========================================="
echo "  ✅ Setup Completato!"
echo "=========================================="
echo ""
echo "Accedi all'applicazione:"
echo "  🌐 Frontend:  http://localhost"
echo "  📡 Backend:   http://localhost:8080"
echo "  🗄️  Database:  localhost:5400 (user: postgres, pass: root)"
echo ""
echo "Verifica i log:"
echo "  docker compose logs -f spring-boot"
echo ""
