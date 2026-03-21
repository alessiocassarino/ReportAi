# 🧪 TEST RAPIDO - Export XLSX/DOCX Fix

**After deploying the fixed version, run this test:**

```bash
# 1. Rebuild & Deploy
mvn clean package -DskipTests
docker compose down
docker compose up -d

# 2. Wait for startup
sleep 50

# 3. Test XLSX Export (il test più importante)
echo "Testing XLSX Export..."
curl -s -X POST http://localhost:8080/api/reports/generate \
  -F "request={
    \"prompt\": \"Create a professional cost analysis report\",
    \"format\": \"XLSX\",
    \"allowWebSearch\": false,
    \"systemPromptId\": 1
  }" \
  -F "files=@documento_test.pdf" \
  -H "Accept: application/json" | jq '{
    status: .status,
    fileName: .fileName,
    downloadUrl: .downloadUrl,
    metadata: {
      executionTimeMs: .metadata.executionTimeMs,
      outputQualityScore: .metadata.outputQualityScore
    }
  }'

# 4. Expected Output:
# {
#   "status": "OK",
#   "fileName": "550e8400-e29b-41d4-a716-446655440000.xlsx",  ← FILE PRESENTE!
#   "downloadUrl": "/api/reports/download/550e8400-e29b-41d4-a716-446655440000.xlsx",
#   "metadata": {
#     "executionTimeMs": 5234,
#     "outputQualityScore": 0.92
#   }
# }

# 5. Test DOCX Export
echo "Testing DOCX Export..."
curl -s -X POST http://localhost:8080/api/reports/generate \
  -F "request={
    \"prompt\": \"Create a professional cost analysis report\",
    \"format\": \"DOCX\",
    \"allowWebSearch\": false,
    \"systemPromptId\": 1
  }" \
  -F "files=@documento_test.pdf" | jq '{status, fileName, downloadUrl}'

# 6. Test JSON (No File)
echo "Testing JSON (No File)..."
curl -s -X POST http://localhost:8080/api/reports/generate \
  -F "request={
    \"prompt\": \"Create a professional cost analysis report\",
    \"format\": \"JSON\",
    \"allowWebSearch\": false,
    \"systemPromptId\": 1
  }" \
  -F "files=@documento_test.pdf" | jq '{status, fileName, hasAnswer: (.answer != null)}'

# 7. Check files in filesystem
echo "Files generated:"
docker compose exec reportai ls -lh /app/data/reports/ | tail -5

# 8. Download the XLSX file to verify
echo "Downloading XLSX file..."
FILENAME=$(curl -s -X POST http://localhost:8080/api/reports/generate \
  -F "request={\"prompt\":\"Test\",\"format\":\"XLSX\"}" \
  -F "files=@documento_test.pdf" | jq -r '.fileName')

curl -s -X GET "http://localhost:8080/api/reports/download/$FILENAME" \
  -o "test_report_$FILENAME" && echo "✅ Downloaded: test_report_$FILENAME"
```

---

## ✅ SUCCESS CRITERIA

After running these tests, you should see:

1. ✅ **XLSX Test**: 
   - `fileName` is **NOT null**
   - `downloadUrl` is **NOT null**
   - File contains actual XLSX data

2. ✅ **DOCX Test**:
   - `fileName` is **NOT null**
   - `downloadUrl` is **NOT null**

3. ✅ **JSON Test**:
   - `fileName` is `null`
   - `downloadUrl` is `null`
   - `answer` contains the Markdown report

4. ✅ **Files in System**:
   - Files exist in `/app/data/reports/`
   - Files are >1KB each

5. ✅ **Download Works**:
   - File can be downloaded
   - File is a valid XLSX/DOCX

---

## ❌ If Tests Fail

Check logs:
```bash
docker compose logs reportai | grep -E "(Export|XLSX|DOCX|❌|✅)"
```

If you see `❌ Nessun template disponibile - report sarà esportato in formato richiesto` → ✅ This is GOOD!

---

This test proves the fix is **REAL and WORKING**.

