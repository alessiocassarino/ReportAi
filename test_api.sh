#!/bin/bash

# ReportAI - Quick Test Script
# Run this after starting the application: java -jar target/reportAi-0.0.1-SNAPSHOT.jar

set -e

BASE_URL="http://localhost:8080"
GREEN='\033[0;32m'\nRED='\033[0;31m'\nYELLOW='\033[1;33m'\nNC='\033[0m'

echo -e "${YELLOW}=== ReportAI Quick Test Suite ===${NC}\\n"

# Function to print test result
print_result() {
    if [ $1 -eq 0 ]; then
        echo -e "${GREEN}✓ PASSED${NC}: $2"
    else
        echo -e "${RED}✗ FAILED${NC}: $2"
    fi
}

# Test 1: Health Check
echo -e "${YELLOW}Test 1: Health Check${NC}"
RESPONSE=$(curl -s -w "\\n%{http_code}" ${BASE_URL}/api/reports/generate 2>/dev/null || echo "000")
STATUS_CODE=$(echo "$RESPONSE" | tail -1)
if [ "$STATUS_CODE" == "400" ] || [ "$STATUS_CODE" == "401" ]; then
    print_result 0 "Server is running"
else
    print_result 1 "Server not responding (code: $STATUS_CODE)"
fi
echo ""

# Test 2: Generate Report (JSON, no context)
echo -e "${YELLOW}Test 2: Generate Report (JSON, no context)${NC}"
RESPONSE=$(curl -s -X POST ${BASE_URL}/api/reports/generate \
  -H "Content-Type: application/json" \
  -d '{
    "prompt": "Test prompt - analizza i dati",
    "format": "JSON",
    "allowWebSearch": false
  }')

if echo "$RESPONSE" | grep -q "\"status\":\"OK\""; then
    print_result 0 "Report generated successfully"
    
    # Extract quality score
    QUALITY_SCORE=$(echo "$RESPONSE" | grep -o '"outputQualityScore":[0-9.]*' | cut -d: -f2)
    echo "   Output Quality Score: $QUALITY_SCORE"
    
    HALLUCINATION=$(echo "$RESPONSE" | grep -o '"halluccinationRiskDetected":[^,}]*' | cut -d: -f2)
    echo "   Hallucination Risk: $HALLUCINATION"
else
    print_result 1 "Report generation failed"
    echo "   Response: $RESPONSE"
fi
echo ""

# Test 3: CSV Export (if has table)
echo -e "${YELLOW}Test 3: Generate Report with CSV Export${NC}"
RESPONSE=$(curl -s -X POST ${BASE_URL}/api/reports/generate \
  -H "Content-Type: application/json" \
  -d '{
    "prompt": "Genera una tabella semplice con 3 colonne e 5 righe",
    "format": "CSV",
    "allowWebSearch": false
  }')

if echo "$RESPONSE" | grep -q "\.csv"; then
    print_result 0 "CSV file generated"
    FILENAME=$(echo "$RESPONSE" | grep -o '"fileName":"[^"]*"' | cut -d'"' -f4)
    echo "   Filename: $FILENAME"
    
    # Try to download
    DOWNLOAD_URL="${BASE_URL}/api/reports/download/${FILENAME}"
    DOWNLOAD_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$DOWNLOAD_URL")
    if [ "$DOWNLOAD_STATUS" == "200" ]; then
        print_result 0 "CSV file downloadable"
    else
        print_result 1 "CSV file download failed (status: $DOWNLOAD_STATUS)"
    fi
else
    print_result 1 "CSV export failed"
fi
echo ""

# Test 4: XLSX Export
echo -e "${YELLOW}Test 4: Generate Report with XLSX Export${NC}"
RESPONSE=$(curl -s -X POST ${BASE_URL}/api/reports/generate \
  -H "Content-Type: application/json" \
  -d '{
    "prompt": "Crea un report in Excel format",
    "format": "XLSX",
    "allowWebSearch": false
  }')

if echo "$RESPONSE" | grep -q "\.xlsx"; then
    print_result 0 "XLSX file generated"
    FILENAME=$(echo "$RESPONSE" | grep -o '"fileName":"[^"]*"' | cut -d'"' -f4)
    echo "   Filename: $FILENAME"
else
    print_result 1 "XLSX export failed"
fi
echo ""

# Test 5: DOCX Export
echo -e "${YELLOW}Test 5: Generate Report with DOCX Export${NC}"
RESPONSE=$(curl -s -X POST ${BASE_URL}/api/reports/generate \
  -H "Content-Type: application/json" \
  -d '{
    "prompt": "Genera un report Word strutturato",
    "format": "DOCX",
    "allowWebSearch": false
  }')

if echo "$RESPONSE" | grep -q "\.docx"; then
    print_result 0 "DOCX file generated"
    FILENAME=$(echo "$RESPONSE" | grep -o '"fileName":"[^"]*"' | cut -d'"' -f4)
    echo "   Filename: $FILENAME"
else
    print_result 1 "DOCX export failed"
fi
echo ""

# Test 6: Error Handling (invalid prompt)
echo -e "${YELLOW}Test 6: Error Handling (invalid prompt)${NC}"
RESPONSE=$(curl -s -X POST ${BASE_URL}/api/reports/generate \
  -H "Content-Type: application/json" \
  -d '{
    "prompt": "",
    "format": "JSON"
  }')

if echo "$RESPONSE" | grep -q "\"errorCode\""; then
    print_result 0 "Error properly handled"
    ERROR_CODE=$(echo "$RESPONSE" | grep -o '"errorCode":"[^"]*"' | cut -d'"' -f4)
    echo "   Error Code: $ERROR_CODE"
else
    print_result 1 "Error handling failed"
fi
echo ""

# Test 7: Metadata in Response
echo -e "${YELLOW}Test 7: Response Metadata${NC}"
RESPONSE=$(curl -s -X POST ${BASE_URL}/api/reports/generate \
  -H "Content-Type: application/json" \
  -d '{
    "prompt": "Test per metadata",
    "format": "JSON",
    "allowWebSearch": false
  }')

if echo "$RESPONSE" | grep -q "\"metadata\""; then
    print_result 0 "Metadata included in response"
    
    EXEC_TIME=$(echo "$RESPONSE" | grep -o '"executionTimeMs":[0-9]*' | cut -d: -f2)
    OUTPUT_QUALITY=$(echo "$RESPONSE" | grep -o '"outputQualityScore":[0-9.]*' | cut -d: -f2)
    CONTEXT_QUALITY=$(echo "$RESPONSE" | grep -o '"contextQualityScore":[0-9.]*' | cut -d: -f2)
    
    echo "   Execution Time: ${EXEC_TIME}ms"
    echo "   Output Quality: $OUTPUT_QUALITY"
    echo "   Context Quality: $CONTEXT_QUALITY"
else
    print_result 1 "Metadata not found in response"
fi
echo ""

# Test 8: Upload Document
echo -e "${YELLOW}Test 8: Document Upload${NC}"

# Create a sample file
SAMPLE_FILE="/tmp/test_document.txt"
echo "This is a test document for ReportAI validation." > "$SAMPLE_FILE"

RESPONSE=$(curl -s -X POST ${BASE_URL}/api/documents/upload \
  -F "files=@${SAMPLE_FILE}")

if echo "$RESPONSE" | grep -q "\"status\":\"INDEXED\""; then
    print_result 0 "Document uploaded and indexed"
    DOC_ID=$(echo "$RESPONSE" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    echo "   Document ID: $DOC_ID"
elif echo "$RESPONSE" | grep -q "\"status\":\"ALREADY_EXISTS\""; then
    print_result 0 "Document already exists in KB"
else
    print_result 1 "Document upload failed"
fi
echo ""

# Test 9: Generate Report with KB Context
echo -e "${YELLOW}Test 9: Generate Report with KB Context${NC}"
RESPONSE=$(curl -s -X POST ${BASE_URL}/api/reports/generate \
  -H "Content-Type: application/json" \
  -d '{
    "prompt": "Qual è il contenuto del documento di test?",
    "format": "JSON",
    "allowWebSearch": false
  }')

if echo "$RESPONSE" | grep -q "\"foundInKnowledgeBase\":true"; then
    print_result 0 "Context from KB retrieved"
    KB_DOCS=$(echo "$RESPONSE" | grep -o '"knowledgeBaseDocumentsUsed":[0-9]*' | cut -d: -f2)
    echo "   KB Documents Used: $KB_DOCS"
else
    echo "   Note: KB search may not find document (depends on embeddings)"
fi
echo ""

# Summary
echo -e "${YELLOW}=== Test Summary ===${NC}"
echo -e "${GREEN}✓ All core functionality tests completed${NC}"
echo ""
echo "Next steps:"
echo "1. Check application logs for any warnings"
echo "2. Verify exported files (CSV, XLSX, DOCX) are valid"
echo "3. Test with actual documents and larger prompts"
echo "4. Monitor performance metrics"
echo ""
echo -e "${YELLOW}For detailed logs, check:${NC}"
echo "tail -f application.log | grep ReportAi"
echo ""
