# ReportAI v1.2.0 - COMPLETE API DOCUMENTATION

**Last Updated**: 21 March 2026  
**Version**: 1.2.0  
**Status**: ✅ PRODUCTION READY  
**Base URL**: `http://localhost:8080`

---

## TABLE OF CONTENTS

1. [API Overview](#api-overview)
2. [Authentication](#authentication)
3. [Error Handling](#error-handling)
4. [Endpoints](#endpoints)
5. [Request/Response Examples](#requestresponse-examples)
6. [Status Codes](#status-codes)
7. [Rate Limiting](#rate-limiting)
8. [Best Practices](#best-practices)

---

## API OVERVIEW

ReportAI provides RESTful APIs for:
- **Document management**: Upload and index documents
- **Report generation**: Create reports with AI
- **File operations**: Download generated reports

### API Characteristics

- **Format**: JSON (request and response bodies)
- **Multipart Support**: Multipart/form-data for file uploads
- **Authentication**: None (internal use recommended)
- **CORS**: Not enabled by default
- **Versioning**: Single version (v1.0 implicit)
- **Pagination**: Not applicable

---

## AUTHENTICATION

Currently, **no authentication** is implemented. 

**Recommendation**: Deploy behind authentication layer (API gateway, OAuth2 proxy)

### For Production

```bash
# Suggested: Use API Gateway (Kong, AWS API Gateway, etc.)
# Or: Add Spring Security OAuth2

# Until then: Use firewall restrictions
iptables -A INPUT -p tcp --dport 8080 -s 10.0.0.0/8 -j ACCEPT
iptables -A INPUT -p tcp --dport 8080 -j DROP
```

---

## ERROR HANDLING

### Error Response Format

All errors return structured JSON:

```json
{
  "errorCode": "INVALID_REQUEST",
  "message": "File upload size exceeds maximum allowed limit",
  "errorType": "CLIENT_ERROR",
  "status": 400,
  "timestamp": "2026-03-21T20:45:30",
  "path": "/api/reports/generate",
  "traceId": "550e8400-e29b-41d4-a716-446655440000",
  "details": "Optional additional details"
}
```

### Error Categories

| ErrorType | HTTP Status | Description |
|-----------|-------------|-------------|
| CLIENT_ERROR | 400, 404, 413 | User request issue |
| SERVER_ERROR | 500 | Server processing error |
| AI_ERROR | 503 | AI service unavailable |
| IO_ERROR | 500 | File system issue |
| VALIDATION_ERROR | 422 | Invalid data format |

### Common Error Codes

| ErrorCode | Status | Cause |
|-----------|--------|-------|
| INVALID_REQUEST | 400 | Malformed JSON or missing fields |
| INVALID_ARGUMENT | 400 | Invalid parameter value |
| INVALID_STATE | 500 | Unexpected application state |
| FILE_NOT_FOUND | 404 | File doesn't exist |
| PAYLOAD_TOO_LARGE | 413 | File > 50MB or total > 200MB |
| IO_ERROR | 500 | Disk read/write failed |
| INTERNAL_ERROR | 500 | Unexpected exception |

### Error Recovery

**Transient Errors** (retry-safe):
- 503 Service Unavailable
- Network timeouts
- Temporary database unavailability

**Permanent Errors** (don't retry):
- 400 Bad Request
- 404 Not Found
- 422 Unprocessable Entity

**Retry Strategy**:
```python
# Exponential backoff: wait 1s, 2s, 4s, 8s
for attempt in range(1, 5):
    try:
        response = requests.post(url, data=data)
        if response.status_code < 500:
            # Don't retry client errors
            break
        # Server error: retry
        time.sleep(2 ** attempt)
    except requests.exceptions.RequestException:
        if attempt < 4:
            time.sleep(2 ** attempt)
        else:
            raise
```

---

## ENDPOINTS

### 1. Generate Report

#### **POST** `/api/reports/generate`

**Description**: Generate an intelligent report using Claude AI

**Authentication**: None

**Content-Type**: `multipart/form-data`

**Request Schema**:

| Part | Type | Required | Description |
|------|------|----------|-------------|
| request | JSON | ✅ Yes | Report request parameters |
| files | File[] | ❌ No | Supplementary documents |
| attachments | JSON | ❌ No | Metadata and hints |

**Request Body Details**:

**Part 1 - `request` (JSON)**:

```json
{
  "prompt": "string (max 2000 chars)",
  "format": "JSON|CSV|XLSX|DOCX|MARKDOWN (default: JSON)",
  "allowWebSearch": "boolean (default: true)",
  "systemPromptId": "integer (optional)",
  "selectedTemplateId": "long (optional)",
  "outputFileName": "string (optional)"
}
```

**Part 2 - `files` (optional)**:
- Multiple files supported
- Max file size: 50MB per file
- Max total request size: 200MB
- Supported formats: PDF, DOCX, XLSX, ODT, RTF, TXT, etc.

**Part 3 - `attachments` (JSON, optional)**:

```json
{
  "attachments": [
    {
      "type": "METADATA_HINTS",
      "data": {
        "countries": ["IT", "DE"],
        "sectors": ["Finance"],
        "clients": ["ClientName"],
        "contractTypes": ["NDA"],
        "keywords": ["payment"]
      }
    }
  ]
}
```

**Response** (HTTP 200):

```json
{
  "status": "OK|ERROR|PARTIAL_SUCCESS",
  "foundInKnowledgeBase": true,
  "webSearchUsed": false,
  "answer": "# Report Title\n\n...",
  "fileName": "550e8400-e29b-41d4-a716-446655440000.xlsx",
  "downloadUrl": "/api/reports/download/550e8400-e29b-41d4-a716-446655440000.xlsx",
  "metadata": {
    "executionTimeMs": 3245,
    "outputQualityScore": 0.92,
    "contextQualityScore": 0.88,
    "knowledgeBaseDocumentsUsed": 5,
    "similarityAverage": 0.76,
    "generationModel": "claude-sonnet-4-6",
    "generatedAt": "2026-03-21T20:45:30",
    "halluccinationRiskDetected": false,
    "validationMessage": "Output quality acceptable"
  }
}
```

**Response Fields**:

- **status**: "OK" (success) or "ERROR" (failed)
- **foundInKnowledgeBase**: Whether KB had relevant documents
- **webSearchUsed**: Whether web search was executed
- **answer**: Generated report in Markdown
- **fileName**: Exported file name (null if format=JSON)
- **downloadUrl**: URL to retrieve exported file
- **metadata.executionTimeMs**: Total processing time
- **metadata.outputQualityScore**: Report quality (0-1)
- **metadata.contextQualityScore**: Context quality (0-1)
- **metadata.halluccinationRiskDetected**: Potential false information?
- **metadata.validationMessage**: Detailed validation feedback

**Status Codes**:

- **200 OK**: Report generated successfully
- **400 Bad Request**: Invalid request format or parameters
- **413 Payload Too Large**: File exceeds limits
- **500 Internal Server Error**: Processing failed
- **503 Service Unavailable**: External service (Claude, Ollama) down

**Timeouts**:

- Default request timeout: 30 seconds
- Claude API timeout: 30 seconds  
- Ollama timeout: 10 seconds
- Total expected: 10-30 seconds

**Example Request** (cURL):

```bash
curl -X POST http://localhost:8080/api/reports/generate \
  -F "request={
    \"prompt\": \"Analyze sales trends and create executive summary\",
    \"format\": \"XLSX\",
    \"allowWebSearch\": true
  }" \
  -F "files=@sales-data.xlsx" \
  -F "files=@competitor-analysis.pdf" \
  -F "attachments={
    \"attachments\": [
      {
        \"type\": \"METADATA_HINTS\",
        \"data\": {
          \"sectors\": [\"Retail\"],
          \"countries\": [\"IT\"]
        }
      }
    ]
  }" \
  -H "Accept: application/json"
```

**Example Request** (Python):

```python
import requests
import json

url = "http://localhost:8080/api/reports/generate"

request_json = {
    "prompt": "Create financial summary",
    "format": "XLSX",
    "allowWebSearch": False
}

files = {
    "request": ("", json.dumps(request_json), "application/json"),
    "files": open("financial-data.xlsx", "rb"),
    "attachments": ("", json.dumps({
        "attachments": [{
            "type": "METADATA_HINTS",
            "data": {"sectors": ["Finance"]}
        }]
    }), "application/json")
}

response = requests.post(url, files=files)
result = response.json()

# Download exported file if available
if result.get("downloadUrl"):
    download_response = requests.get(
        f"http://localhost:8080{result['downloadUrl']}"
    )
    with open("report.xlsx", "wb") as f:
        f.write(download_response.content)
```

---

### 2. Upload Documents

#### **POST** `/api/documents/upload`

**Description**: Upload documents to knowledge base for indexing

**Authentication**: None

**Content-Type**: `multipart/form-data`

**Request**:

```
POST /api/documents/upload HTTP/1.1
Host: localhost:8080
Content-Type: multipart/form-data; boundary=----FormBoundary

------FormBoundary
Content-Disposition: form-data; name="files"; filename="document1.pdf"
Content-Type: application/pdf

[PDF binary data]
------FormBoundary
Content-Disposition: form-data; name="files"; filename="document2.docx"
Content-Type: application/vnd.openxmlformats-officedocument.wordprocessingml.document

[DOCX binary data]
------FormBoundary--
```

**Request Parameters**:

- **files** (required): Form field containing file(s) to upload
  - Multiple files supported: name same field multiple times
  - Max file size: 50MB per file
  - Max total: 200MB
  - Supported: PDF, DOCX, XLSX, ODT, TXT, RTF, etc.

**Response** (HTTP 200):

```json
[
  {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "filename": "document1.pdf",
    "status": "INDEXED"
  },
  {
    "id": "550e8400-e29b-41d4-a716-446655440001",
    "filename": "document2.docx",
    "status": "INDEXED"
  },
  {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "filename": "duplicate.pdf",
    "status": "ALREADY_EXISTS"
  }
]
```

**Response Fields**:

- **id**: Unique document identifier (UUID)
- **filename**: Original filename
- **status**:
  - "INDEXED": Document newly indexed
  - "ALREADY_EXISTS": Duplicate (same SHA256 hash)

**Status Codes**:

- **200 OK**: All files processed
- **400 Bad Request**: No files provided or invalid format
- **413 Payload Too Large**: Exceeds size limits
- **500 Internal Server Error**: Processing failed

**Processing Time**: 5-30 seconds per document (depends on file size)

**Example Request** (cURL):

```bash
curl -X POST http://localhost:8080/api/documents/upload \
  -F "files=@document1.pdf" \
  -F "files=@document2.docx" \
  -F "files=@spreadsheet.xlsx" \
  -H "Accept: application/json"
```

**Example Request** (Python):

```python
import requests

url = "http://localhost:8080/api/documents/upload"

files = [
    ("files", ("document1.pdf", open("document1.pdf", "rb"))),
    ("files", ("document2.docx", open("document2.docx", "rb"))),
]

response = requests.post(url, files=files)
result = response.json()

for doc in result:
    print(f"{doc['filename']}: {doc['status']} (ID: {doc['id']})")
```

---

### 3. Download Report File

#### **GET** `/api/reports/download/{fileName}`

**Description**: Download a previously generated report file

**Authentication**: None

**Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| fileName | path | ✅ Yes | File name from ReportResponse.fileName |

**Response** (HTTP 200):

- **Content-Type**: Based on file extension
  - `.xlsx`: `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`
  - `.docx`: `application/vnd.openxmlformats-officedocument.wordprocessingml.document`
  - `.csv`: `text/csv; charset=utf-8`
  - `.json`: `application/json`

- **Content-Disposition**: `attachment; filename="{fileName}"`

- **Body**: Binary file content

**Status Codes**:

- **200 OK**: File found and returned
- **404 Not Found**: File doesn't exist
- **500 Internal Server Error**: Read error

**Example Request** (cURL):

```bash
curl -X GET "http://localhost:8080/api/reports/download/550e8400-e29b-41d4-a716-446655440000.xlsx" \
  -H "Accept: application/octet-stream" \
  -o "report.xlsx"
```

**Example Request** (Python):

```python
import requests

url = "http://localhost:8080/api/reports/download/550e8400-e29b-41d4-a716-446655440000.xlsx"

response = requests.get(url)

if response.status_code == 200:
    with open("downloaded_report.xlsx", "wb") as f:
        f.write(response.content)
    print("File downloaded successfully")
else:
    print(f"Error: {response.status_code}")
```

---

## REQUEST/RESPONSE EXAMPLES

### Example 1: Simple Report Generation

**Scenario**: Generate a basic report without attachments

**Request**:

```bash
curl -X POST http://localhost:8080/api/reports/generate \
  -F "request={\"prompt\":\"Summarize the key points\",\"format\":\"JSON\"}" \
  -H "Accept: application/json"
```

**Response**:

```json
{
  "status": "OK",
  "foundInKnowledgeBase": false,
  "webSearchUsed": false,
  "answer": "I don't have any context available to summarize...",
  "fileName": null,
  "downloadUrl": null,
  "metadata": {
    "executionTimeMs": 2150,
    "outputQualityScore": 0.45,
    "contextQualityScore": 0.0,
    "knowledgeBaseDocumentsUsed": 0,
    "similarityAverage": 0.0,
    "generationModel": "claude-sonnet-4-6",
    "generatedAt": "2026-03-21T20:45:30",
    "halluccinationRiskDetected": true,
    "validationMessage": "No context available for query"
  }
}
```

---

### Example 2: Report with Knowledge Base

**Scenario**: Generate report using indexed documents

**Request**:

```bash
curl -X POST http://localhost:8080/api/reports/generate \
  -F "request={
    \"prompt\":\"Create executive summary of all contracts\",
    \"format\":\"XLSX\"
  }" \
  -H "Accept: application/json"
```

**Response**:

```json
{
  "status": "OK",
  "foundInKnowledgeBase": true,
  "webSearchUsed": false,
  "answer": "# Contract Summary\n\n## Overview\nAnalyzed 5 contracts from the knowledge base...",
  "fileName": "550e8400-e29b-41d4-a716-446655440000.xlsx",
  "downloadUrl": "/api/reports/download/550e8400-e29b-41d4-a716-446655440000.xlsx",
  "metadata": {
    "executionTimeMs": 5234,
    "outputQualityScore": 0.89,
    "contextQualityScore": 0.86,
    "knowledgeBaseDocumentsUsed": 5,
    "similarityAverage": 0.78,
    "generationModel": "claude-sonnet-4-6",
    "generatedAt": "2026-03-21T20:45:30",
    "halluccinationRiskDetected": false,
    "validationMessage": "Output quality acceptable"
  }
}
```

---

### Example 3: Report with Temporary Files

**Scenario**: Generate report with uploaded files + knowledge base

**Request**:

```bash
curl -X POST http://localhost:8080/api/reports/generate \
  -F "request={
    \"prompt\":\"Compare uploaded Q3 results with KB historical data\",
    \"format\":\"DOCX\"
  }" \
  -F "files=@Q3-2025-results.xlsx" \
  -F "files=@Q3-2024-results.xlsx" \
  -H "Accept: application/json"
```

**Response**:

```json
{
  "status": "OK",
  "foundInKnowledgeBase": true,
  "webSearchUsed": false,
  "answer": "# Quarterly Comparison: Q3 2025 vs Q3 2024\n\n## Performance Analysis\n...",
  "fileName": "550e8400-e29b-41d4-a716-446655440002.docx",
  "downloadUrl": "/api/reports/download/550e8400-e29b-41d4-a716-446655440002.docx",
  "metadata": {
    "executionTimeMs": 6789,
    "outputQualityScore": 0.94,
    "contextQualityScore": 0.91,
    "knowledgeBaseDocumentsUsed": 3,
    "similarityAverage": 0.82,
    "generationModel": "claude-sonnet-4-6",
    "generatedAt": "2026-03-21T20:45:30",
    "halluccinationRiskDetected": false,
    "validationMessage": "Output quality acceptable"
  }
}
```

---

### Example 4: Document Upload

**Scenario**: Upload multiple documents

**Request**:

```bash
curl -X POST http://localhost:8080/api/documents/upload \
  -F "files=@contract1.pdf" \
  -F "files=@contract2.pdf" \
  -F "files=@policy.docx" \
  -H "Accept: application/json"
```

**Response**:

```json
[
  {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "filename": "contract1.pdf",
    "status": "INDEXED"
  },
  {
    "id": "550e8400-e29b-41d4-a716-446655440001",
    "filename": "contract2.pdf",
    "status": "INDEXED"
  },
  {
    "id": "550e8400-e29b-41d4-a716-446655440002",
    "filename": "policy.docx",
    "status": "INDEXED"
  }
]
```

---

## STATUS CODES

### Success Codes

| Code | Description |
|------|-------------|
| **200** | Request successful, response in body |
| **201** | Resource created |
| **204** | No content (success with no body) |

### Client Error Codes

| Code | Description | When |
|------|-------------|------|
| **400** | Bad Request | Invalid JSON, missing fields, wrong format |
| **404** | Not Found | File doesn't exist, endpoint not found |
| **413** | Payload Too Large | File >50MB or request >200MB |
| **422** | Unprocessable Entity | Valid format but semantically invalid |

### Server Error Codes

| Code | Description | When |
|------|-------------|------|
| **500** | Internal Server Error | Unexpected exception, disk failure |
| **503** | Service Unavailable | Claude API down, Ollama unreachable |

---

## RATE LIMITING

Currently **not implemented**.

### Recommended Limits for Production

```
# Per IP address
- 100 requests per minute
- 1000 requests per hour
- 10000 requests per day

# Per endpoint
- /api/reports/generate: 30 req/min (expensive)
- /api/documents/upload: 50 req/min (storage limited)
- /api/reports/download: 100 req/min (fast)
```

### Implementation

Use API Gateway or Spring Cloud Gateway:

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: reportai
          uri: http://localhost:8080
          predicates:
            - Path=/api/**
          filters:
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 100
                redis-rate-limiter.burstCapacity: 200
```

---

## BEST PRACTICES

### Request Design

1. **Prompt Writing**
   - Be specific and clear
   - Include desired format/structure
   - Mention key constraints
   - Example: "Create a table with client name, amount, and payment terms"

2. **File Selection**
   - Upload only relevant documents
   - Larger KB = slower search
   - Consider document freshness
   - Clean up old documents regularly

3. **Format Selection**
   - JSON: API integration, fastest
   - CSV: Data analysis, bulk import
   - XLSX: Business reports, formatted
   - DOCX: Professional documents, printing

### Error Handling

```python
import requests
import time

def generate_report_with_retry(prompt, format="JSON", max_retries=3):
    url = "http://localhost:8080/api/reports/generate"
    
    for attempt in range(max_retries):
        try:
            response = requests.post(url, files={
                "request": ("", json.dumps({
                    "prompt": prompt,
                    "format": format
                }), "application/json")
            }, timeout=60)
            
            # Handle specific status codes
            if response.status_code == 200:
                return response.json()
            elif response.status_code == 413:
                print("Files too large")
                break
            elif response.status_code >= 500:
                # Retry on server error
                wait = (2 ** attempt)
                print(f"Server error, retrying in {wait}s...")
                time.sleep(wait)
                continue
            else:
                # Don't retry client errors
                print(f"Client error: {response.status_code}")
                break
                
        except requests.exceptions.Timeout:
            print("Request timeout, retrying...")
            time.sleep(2 ** attempt)
        except Exception as e:
            print(f"Unexpected error: {e}")
            break
    
    return None
```

### Performance Optimization

1. **Cache Uploads**
   - Don't re-upload same documents
   - System deduplicates via SHA256
   - Check status in response

2. **Batch Similar Requests**
   - Process multiple reports sequentially
   - Reuse knowledge base
   - Avoid concurrent overload

3. **Monitor Quality Scores**
   - Track outputQualityScore trends
   - If <0.6: Improve prompt or KB
   - If >0.9: Validate results carefully

4. **Use Metadata Hints**
   - Improves search ranking
   - Faster, better results
   - Specify countries, sectors, clients when known

