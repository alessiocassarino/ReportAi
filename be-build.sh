#!/bin/bash
# ============================================================================
# Development Build Script
# Aggiorna il backend senza perdere i volumi (Ollama, DB, ecc.)
# ============================================================================

echo "🔧 Development Build - Aggiornamento Backend"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Step 1: Ferma i container (senza rimuovere i volumi)
echo "📍 Step 1: Arresto container..."
docker-compose down

# Step 2: Ricompila solo il backend
echo "📍 Step 2: Build del backend..."
docker-compose build spring-boot

# Step 3: Riavvia tutti i servizi
echo "📍 Step 3: Avvio container..."
docker-compose up -d

# Step 4: Mostra i log
echo ""
echo "✅ Build completato!"
echo ""
echo "📊 Container Status:"
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"

echo ""
echo "📋 Backend Logs (ultimi 20 righe):"
docker logs --tail 20 report-ai-backend

echo ""
echo "✨ Quando vedi 'Tomcat initialized with port 8080' il backend è pronto!"
