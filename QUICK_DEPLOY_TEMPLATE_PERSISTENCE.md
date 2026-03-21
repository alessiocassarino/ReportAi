# 🚀 QUICK DEPLOY GUIDE - Template Persistence

**Version**: 1.2.1 with Template Persistence  
**Date**: 21 March 2026

---

## ⚡ Quick Start (5 minutes)

### 1. Rebuild JAR
```bash
cd /path/to/reportAi
mvn clean package -DskipTests
```

**Expected Output**:
```
BUILD SUCCESS
Total time: ~24 seconds
Errors: 0
```

### 2. Set Environment & Deploy
```bash
export ANTHROPIC_API_KEY="sk-ant-..."
docker compose down
docker compose up -d
```

### 3. Wait for Startup (40-50 seconds)
```bash
docker compose ps
# Wait until all containers show HEALTHY/running status
```

### 4. Verify API
```bash
curl http://localhost:8080/health
# Should return: {"status":"UP",...}
```

---

## 🧪 Test Template Persistence

### Test 1: Upload Report WITH Template (Auto-Save)

Create test files first:
```bash
# Create a minimal test PDF
echo "Test Document" > test_doc.txt
# (Use any PDF file you have, or create with LibreOffice)

# Use any XLSX file as template
# (Download from Excel or use existing)
```

Then test:
```bash
curl -X POST http://localhost:8080/api/reports/generate \
  -F "request={
    \"prompt\": \"Create an executive summary of this document with key findings and recommendations\",
    \"format\": \"XLSX\",
    \"allowWebSearch\": false,
    \"systemPromptId\": 1
  }" \
  -F "files=@test_doc.pdf" \
  -F "files=@template.xlsx" \
  -H "Accept: application/json" | jq '.'
```

**Expected Response**:
```json
{
  "status": "OK",
  "foundInKnowledgeBase": true,
  "webSearchUsed": false,
  "answer": "# Executive Summary...",
  "fileName": "550e8400-e29b-41d4-a716-446655440000.xlsx",
  "downloadUrl": "/api/reports/download/550e8400-e29b-41d4-a716-446655440000.xlsx",
  "metadata": {
    "executionTimeMs": 5234,
    "outputQualityScore": 0.92,
    ...
  }
}
```

**Check Logs**:
```bash
docker compose logs reportai | grep "Template"
# Should see:
# ✅ Template trovato negli attachments -> id=1
# ✅ Template SALVATO nel DB per riutilizzo futuro
```

---

### Test 2: Reuse Template (selectedTemplateId)

```bash
curl -X POST http://localhost:8080/api/reports/generate \
  -F "request={
    \"prompt\": \"Create a technical analysis with detailed findings\",
    \"format\": \"XLSX\",
    \"selectedTemplateId\": 1,
    \"allowWebSearch\": false
  }" \
  -F "files=@test_doc2.pdf" \
  -H "Accept: application/json" | jq '.'
```

**Expected Response**:
- Different `fileName` (new report)
- Same template used (from DB)
- No re-upload needed

**Check Logs**:
```bash
docker compose logs reportai | grep "Template"
# Should see:
# ✅ Template trovato nel request (selectedTemplateId) -> id=1
# ✅ Template RIUTILIZZATO dal DB
```

---

### Test 3: Auto-Detection (Upload Again)

```bash
curl -X POST http://localhost:8080/api/reports/generate \
  -F "request={
    \"prompt\": \"Final comprehensive report\",
    \"format\": \"XLSX\",
    \"allowWebSearch\": false
  }" \
  -F "files=@test_doc3.pdf" \
  -F "files=@template.xlsx" \
  -H "Accept: application/json" | jq '.'
```

**Check Logs**:
```bash
docker compose logs reportai | grep "Template"
# Should see:
# ✅ Template AUTO-DETECTED per format XLSX
```

---

## 📊 Verify Database

```bash
# Check if templates are saved
docker compose exec postgres psql -U postgres -d vector_db -c "
  SELECT id, name, template_type, sha256, active 
  FROM report_template 
  ORDER BY created_at DESC 
  LIMIT 5;
"

# Expected Output:
#  id |     name     | template_type |                sha256                | active
# ----|-----|-----------|-----------------------------------|--------|
#  1  | template.xlsx |    EXCEL      | abc123def456... | t
```

---

## 📥 Download Report

```bash
# Replace with actual fileName from response
curl -X GET http://localhost:8080/api/reports/download/550e8400-e29b-41d4-a716-446655440000.xlsx \
  -o my_report.xlsx

# File should be saved as: my_report.xlsx
# Open with Excel to verify template was applied
```

---

## 🔍 Troubleshooting

### Containers not starting?
```bash
docker compose logs
# Check for errors in logs

docker compose ps
# Verify container status

# Restart
docker compose restart reportai
```

### Template not being saved?
```bash
docker compose logs reportai | grep -E "(Template|Error|Exception)"
# Check for error messages

# Check if attachments were processed
docker compose logs reportai | grep "Processing attachment"
```

### Database not accessible?
```bash
docker compose exec postgres psql -U postgres -d vector_db -c "SELECT 1;"
# Should return: 1

# If fails, restart postgres
docker compose restart postgres
```

### Report file not downloading?
```bash
# Verify file exists
docker compose exec reportai ls -la /app/data/reports/

# Check permissions
docker compose exec reportai chmod 644 /app/data/reports/*
```

---

## 📋 Checklist

After deployment, verify:

- [ ] Containers are running: `docker compose ps`
- [ ] API responds: `curl http://localhost:8080/health`
- [ ] Upload works: Test 1 successful
- [ ] Reuse works: Test 2 successful
- [ ] Auto-detection works: Test 3 successful
- [ ] Database has templates: `SELECT COUNT(*) FROM report_template`
- [ ] Reports downloadable: Files in `/app/data/reports/`
- [ ] No errors in logs: `docker compose logs reportai`

---

## 🎯 Next Steps

### If everything works:
1. ✅ Test with your actual pipeline template
2. ✅ Verify DOCX format works
3. ✅ Test with large documents
4. ✅ Load test with multiple concurrent requests

### If issues occur:
1. 📋 Check logs: `docker compose logs reportai`
2. 📊 Check database: `SELECT * FROM report_template`
3. 🔄 Restart: `docker compose restart`
4. 🔧 Check disk space: `docker system df`

---

## 📞 For Help

**Check Documentation**:
- `TEMPLATE_PERSISTENCE_IMPLEMENTATION.md` - Detailed technical info
- `COMPLETE_ARCHITECTURE.md` - System architecture
- `API_DOCUMENTATION.md` - API reference

**Review Logs**:
```bash
# Follow logs in real-time
docker compose logs reportai -f | grep -E "(Template|✅|Error)"

# Search for specific template operations
docker compose logs reportai | grep -i "template"
```

---

**Version**: 1.2.1  
**Status**: ✅ Ready for Testing  
**Estimated Time**: 5-10 minutes

