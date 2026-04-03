#!/bin/bash
# ============================================================================
# Clean Slate Script
# Rimuove TUTTO (container, immagini, volumi) per un reset completo
# ⚠️ ATTENZIONE: Questo cancella TUTTI i dati!
# ============================================================================

echo ""
echo "⚠️  ATTENZIONE: Questo comando rimuoverà:"
echo "   - Tutti i container (report-ai-*)"
echo "   - Tutti i volumi (database, ollama, ecc.)"
echo "   - Tutti i dati salvati"
echo ""
read -p "Sei sicuro? [s/n] " -n 1 -r
echo

if [[ ! $REPLY =~ ^[Ss]$ ]]; then
    echo "❌ Operazione annullata"
    exit 1
fi

echo ""
echo "🔧 Clean Slate - Reset Completo"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# Rimuovi tutto
echo "📍 Rimozione container e volumi..."
docker-compose down -v --rmi all

echo ""
echo "🗑️  Pulizia ulteriore (immagini dangling)..."
docker image prune -f

echo ""
echo "✅ Clean slate completato!"
echo ""
echo "Adesso puoi fare:"
echo "   docker-compose up -d --build"
echo ""
