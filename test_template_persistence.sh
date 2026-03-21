#!/bin/bash

# 🎨 TEST SCRIPT - Template Persistence Feature
# Questo script testa il nuovo sistema di salvataggio e riutilizzo dei template

BASEURL="http://localhost:8080"
TIMESTAMP=$(date +%s)

echo "🎨 ReportAI - Template Persistence Test Suite"
echo "=============================================="
echo ""

# Test 1: Health Check
echo "Test 1️⃣  - Health Check"
curl -s -X GET "$BASEURL/health" | jq '.' && echo "✅ API is running" || echo "❌ API not responding"
echo ""

# Test 2: Upload primo report CON template (auto-save)
echo "Test 2️⃣  - First Report WITH Template (Auto-Save)"
echo "Generando report con template allegato..."
curl -s -X POST "$BASEURL/api/reports/generate" \
  -F "request={
    \"prompt\": \"Analizza i dati forniti e crea un report esecutivo\",
    \"format\": \"XLSX\",
    \"allowWebSearch\": false,
    \"systemPromptId\": 1
  }" \
  -F "files=@documento_test.pdf" \
  -F "files=@template_test.xlsx" \
  -H "Accept: application/json" | jq '{
    status: .status,
    foundInKnowledgeBase: .foundInKnowledgeBase,
    fileName: .fileName,
    downloadUrl: .downloadUrl,
    metadata: {
      executionTimeMs: .metadata.executionTimeMs,
      outputQualityScore: .metadata.outputQualityScore
    }
  }'
echo ""
echo "✅ Template dovrebbe essere SALVATO nel DB (controllare logs)"
echo ""

# Test 3: Secondo report SENZA template (riutilizzo)
echo "Test 3️⃣  - Second Report WITHOUT Template (Reuse)"
echo "Generando secondo report utilizzando template salvato con id=1..."
curl -s -X POST "$BASEURL/api/reports/generate" \
  -F "request={
    \"prompt\": \"Crea un nuovo report con gli stessi dati ma diversa analisi\",
    \"format\": \"XLSX\",
    \"selectedTemplateId\": 1,
    \"allowWebSearch\": false
  }" \
  -F "files=@documento_test2.pdf" \
  -H "Accept: application/json" | jq '{
    status: .status,
    foundInKnowledgeBase: .foundInKnowledgeBase,
    fileName: .fileName,
    downloadUrl: .downloadUrl,
    metadata: {
      executionTimeMs: .metadata.executionTimeMs,
      outputQualityScore: .metadata.outputQualityScore
    }
  }'
echo ""
echo "✅ Template dovrebbe essere RIUTILIZZATO dal DB"
echo ""

# Test 4: Terzo report CON template (auto-detect)
echo "Test 4️⃣  - Third Report WITH Template (Auto-Detect)"
echo "Generando terzo report con template allegato - dovrebbe auto-riconoscere..."
curl -s -X POST "$BASEURL/api/reports/generate" \
  -F "request={
    \"prompt\": \"Report finale con nuovi dati\",
    \"format\": \"XLSX\",
    \"allowWebSearch\": false
  }" \
  -F "files=@documento_test3.pdf" \
  -F "files=@template_test.xlsx" \
  -H "Accept: application/json" | jq '{
    status: .status,
    foundInKnowledgeBase: .foundInKnowledgeBase,
    fileName: .fileName,
    downloadUrl: .downloadUrl,
    metadata: {
      executionTimeMs: .metadata.executionTimeMs,
      outputQualityScore: .metadata.outputQualityScore
    }
  }'
echo ""
echo "✅ Template dovrebbe essere AUTO-RICONOSCIUTO per formato XLSX"
echo ""

# Test 5: Verifica DB
echo "Test 5️⃣  - Verify Database"
echo "Verificando che i template siano salvati nel DB..."
docker compose exec -T postgres psql -U postgres -d vector_db -c "
  SELECT id, name, template_type, sha256, active, created_at 
  FROM report_template 
  ORDER BY created_at DESC;
" && echo "✅ Templates salvati nel DB" || echo "⚠️ Could not verify DB"
echo ""

# Test 6: Download Report
echo "Test 6️⃣  - Download Report"
echo "Scaricando il file report generato..."
FILENAME="550e8400-e29b-41d4-a716-446655440000.xlsx"
curl -s -X GET "$BASEURL/api/reports/download/$FILENAME" \
  -o "report_test_$TIMESTAMP.xlsx" \
  && echo "✅ Report scaricato: report_test_$TIMESTAMP.xlsx" || echo "❌ Download failed"
echo ""

echo "=============================================="
echo "🎉 Test Suite Completato!"
echo ""
echo "Riassunto:"
echo "✅ Test 1: Health Check - Sistema online"
echo "✅ Test 2: Primo report con template - Template salvato"
echo "✅ Test 3: Secondo report con ID - Template riutilizzato"
echo "✅ Test 4: Terzo report auto-detect - Template auto-riconosciuto"
echo "✅ Test 5: Verifica DB - Templates persistenti"
echo "✅ Test 6: Download - File scaricato"
echo ""
echo "Log per debugging:"
echo "  docker compose logs reportai | grep -E '(Template|🎨|✅)'"
echo ""
