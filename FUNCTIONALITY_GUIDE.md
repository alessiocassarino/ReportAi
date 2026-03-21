# ReportAI v1.2.0 - COMPLETE FUNCTIONALITY GUIDE

**Last Updated**: 21 March 2026  
**Version**: 1.2.0  
**Status**: ✅ PRODUCTION READY

---

## TABLE OF CONTENTS

1. [Feature Overview](#feature-overview)
2. [Document Management](#document-management)
3. [Report Generation](#report-generation)
4. [Context Retrieval](#context-retrieval)
5. [Export Formats](#export-formats)
6. [Quality Assurance](#quality-assurance)
7. [Web Search Integration](#web-search-integration)
8. [Template System](#template-system)
9. [Advanced Features](#advanced-features)

---

## FEATURE OVERVIEW

### Core Capabilities

| Feature | Status | Description |
|---------|--------|-------------|
| **Document Ingestion** | ✅ Active | Upload and index documents automatically |
| **Semantic Search** | ✅ Active | Find relevant documents using AI embeddings |
| **AI Report Generation** | ✅ Active | Generate reports with Claude AI |
| **Web Search Fallback** | ✅ Available | Supplement context with web results |
| **Quality Validation** | ✅ Active | Detect hallucinations and quality issues |
| **Context Scoring** | ✅ Active | Measure quality of retrieved context |
| **Multi-Format Export** | ✅ Active | Export as CSV, XLSX, DOCX, or JSON |
| **Template Rendering** | ✅ Ready | Apply custom templates to reports |
| **Metadata Extraction** | ✅ Active | Automatically categorize documents |
| **File Deduplication** | ✅ Active | Prevent duplicate document storage |
| **Request-Scoped Temp Files** | ✅ Active | Temporary file isolation per request |
| **Comprehensive Logging** | ✅ Active | Debug and monitor all operations |

---

## DOCUMENT MANAGEMENT

### Upload & Ingestion

**Endpoint**: `POST /api/documents/upload`

**Purpose**: Upload documents to the knowledge base for indexing and semantic search

**Supported Formats**:
- PDF (application/pdf)
- Word (application/vnd.openxmlformats-officedocument.wordprocessingml.document)
- Text (text/plain)
- Excel (application/vnd.openxmlformats-officedocument.spreadsheetml.sheet)
- ODT (application/vnd.oasis.opendocument.text)
- RTF (application/rtf)
- Any format supported by Apache Tika

**Request**:

```bash
curl -X POST http://localhost:8080/api/documents/upload \
  -F "files=@document1.pdf" \
  -F "files=@document2.docx" \
  -H "Accept: application/json"
```

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
  }
]
```

### Processing Pipeline

When a document is uploaded:

1. **Validation**
   - File must not be empty
   - Content type must be supported

2. **Deduplication**
   - Calculate SHA256 hash of file contents
   - Check if already exists in database
   - If exists: return existing entry with status "ALREADY_EXISTS"

3. **Storage**
   - Save file to disk with UUID-based filename
   - Format: `{UUID}_{original-name}`
   - Location: `./data/uploads/`

4. **Text Extraction**
   - Use Apache Tika to extract text from any format
   - Handles embedded metadata
   - Preserves document structure information

5. **Metadata Extraction**
   - AI analysis to extract:
     - Document type (contract, report, policy, etc.)
     - Language (from content analysis)
     - Country (geographical hints)
     - Client name (if mentioned)
     - Project name (if relevant)
     - Business sector
     - Contract type (if applicable)
     - Document date
     - Version information
     - Custom tags

6. **Chunking**
   - Split document into 300-token chunks
   - 50-token overlap between chunks
   - Preserve metadata for each chunk

7. **Embedding Generation**
   - Generate embeddings using Ollama
   - Model: `mxbai-embed-large:latest`
   - Dimensions: 1024
   - Stored in PGVector for similarity search

8. **Indexing**
   - Insert chunks into vector store
   - Store metadata in PostgreSQL
   - Index for fast retrieval

**Database Storage**:

- **stored_file**: One record per document
  - ID, filename, content type, SHA256, size, storage path, extracted text, status
  
- **document_metadata**: Detailed metadata
  - Type, language, country, client, sector, contract type, date, version, tags

- **vector_store**: One record per chunk
  - Content, embedding (vector), metadata, timestamps

---

## REPORT GENERATION

### Generate Report

**Endpoint**: `POST /api/reports/generate`

**Purpose**: Generate an intelligent report using Claude AI with context from knowledge base, uploaded files, and optionally web search

**Request Format**: `multipart/form-data`

**Request Parts**:

#### Part 1: `request` (JSON)

```json
{
  "prompt": "Analizza i contratti di fornitura e crea un report sulle condizioni di pagamento",
  "format": "XLSX",
  "allowWebSearch": false,
  "systemPromptId": null,
  "selectedTemplateId": null,
  "outputFileName": "payment-conditions-report"
}
```

**Field Descriptions**:

- **prompt** (string, required)
  - User's instructions for report generation
  - Max 2000 characters
  - Should describe what you want in the report
  - Examples:
    - "Create executive summary of Q4 financial results"
    - "Compare competitor pricing strategies"
    - "Extract key dates and milestones from contracts"

- **format** (string, optional, default: "JSON")
  - Output format for exported file
  - Options: JSON, CSV, XLSX, DOCX, MARKDOWN
  - JSON: Report stays in response, no file created
  - CSV: Semicolon-separated (for Italian Excel)
  - XLSX: Formatted Excel workbook with styles
  - DOCX: Formatted Word document with headings

- **allowWebSearch** (boolean, optional, default: true)
  - If true and knowledge base doesn't have good results
  - Application will search the web via Tavily API
  - Requires app.web-search.enabled=true
  - Requires web-search API key configured

- **systemPromptId** (integer, optional)
  - ID of custom system prompt in database
  - If not specified, uses default system prompt
  - Allows customization of AI behavior
  - Use cases: different tones, languages, styles

- **selectedTemplateId** (long, optional)
  - ID of template to apply to generated report
  - If specified, report is rendered through template
  - Enables standardized report formatting
  - Use cases: branded headers, specific sections

- **outputFileName** (string, optional)
  - Custom name for exported file (without extension)
  - If not specified, UUID is used
  - Extension added automatically based on format

#### Part 2: `files` (optional)

Multipart file attachments to include in this request:

```bash
-F "files=@sales-data.xlsx"
-F "files=@contracts.pdf"
-F "files=@templates.docx"
```

**Features**:
- Files are processed temporarily for this request only
- Embeddings generated and indexed in temporary store
- Combined with persistent knowledge base results
- Automatically cleaned up after report generation

#### Part 3: `attachments` (optional)

Additional metadata hints to improve search:

```json
{
  "attachments": [
    {
      "type": "METADATA_HINTS",
      "data": {
        "countries": ["IT", "DE"],
        "sectors": ["Finance", "Insurance"],
        "clients": ["UniCredit", "Intesa Sanpaolo"]
      }
    }
  ]
}
```

### Complete Request Example

```bash
curl -X POST http://localhost:8080/api/reports/generate \
  -F "request={
    \"prompt\": \"Create executive summary of Q4 sales performance with focus on top clients\",
    \"format\": \"XLSX\",
    \"allowWebSearch\": true
  }" \
  -F "files=@Q4-sales-data.xlsx" \
  -F "files=@client-list.pdf" \
  -F "attachments={
    \"attachments\": [
      {
        \"type\": \"METADATA_HINTS\",
        \"data\": {
          \"sectors\": [\"Finance\", \"Tech\"],
          \"countries\": [\"IT\"]
        }
      }
    ]
  }" \
  -H "Accept: application/json"
```

### Response Structure

**Success Response** (HTTP 200):

```json
{
  "status": "OK",
  "foundInKnowledgeBase": true,
  "webSearchUsed": false,
  "answer": "# Q4 2024 Sales Performance Report\n\n## Executive Summary\nQ4 2024 was a strong quarter for the company...",
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

**Field Descriptions**:

- **status**: "OK" = successful, "ERROR" = failed
- **foundInKnowledgeBase**: Were documents found in local KB?
- **webSearchUsed**: Was web search executed?
- **answer**: The generated report (Markdown format)
- **fileName**: Name of exported file (if applicable)
- **downloadUrl**: URL to download the file
- **metadata**: Execution metrics and quality scores

### Processing Workflow

```
1. Parse Request
   ├─ Extract prompt, format, flags, template
   ├─ Validate inputs
   └─ Prepare for processing

2. Process Attachments
   ├─ Extract text and metadata
   ├─ Generate embeddings (temporary index)
   └─ Store for this request only

3. Search Knowledge Base
   ├─ Semantic similarity search
   ├─ Re-rank by metadata hints
   └─ Return top 5 most relevant documents

4. Determine Web Search Need
   ├─ If KB found results → Use KB only
   ├─ If KB empty + web enabled → Search web
   └─ If KB empty + web disabled → Proceed without

5. Assemble Context
   ├─ Combine KB documents
   ├─ Add temporary attachments
   ├─ Integrate web results
   ├─ Add template description
   └─ Include metadata hints

6. Score Context Quality
   ├─ Measure similarity scores
   ├─ Check document coverage
   ├─ Calculate risk metrics
   └─ Generate quality score (0-1)

7. Generate Report
   ├─ Select system prompt (default or custom)
   ├─ Add hallucination warning if no context
   ├─ Call Claude AI API
   ├─ Receive generated content
   └─ Validate output quality

8. Validate Output
   ├─ Check length (100-50000 chars)
   ├─ Check hallucination indicators
   ├─ Calculate quality score
   └─ If critical issue: Regenerate with enhanced prompt

9. Render Template (if specified)
   ├─ Apply template formatting
   ├─ Inject generated content
   └─ Generate structured output

10. Export (if format != JSON)
    ├─ Format as CSV / XLSX / DOCX
    ├─ Save to disk
    ├─ Return file metadata
    └─ Return download URL

11. Cleanup
    ├─ Delete temporary files
    ├─ Release temporary resources
    └─ Return response with metrics
```

---

## CONTEXT RETRIEVAL

### Vector Store Search

**How It Works**:

1. **Semantic Similarity Search**
   - Query is embedded using Ollama
   - Top 12 documents retrieved by cosine similarity
   - Similarity threshold: 0.4 (40%)
   - Ranked by embedding distance

2. **Metadata-Based Re-ranking**
   - Documents re-scored based on metadata hints
   - Scoring system:
     - Document type match: +30 points
     - Country match: +20 points
     - Client name match: +25 points
     - Sector match: +15 points
     - Contract type match: +15 points
     - Keywords in tags: +2 points each
   - Final ranking: Combined score

3. **Result Limiting**
   - Top 5 documents selected after re-ranking
   - Returned in relevance order
   - Each document includes full metadata

### Metadata Hints

Metadata hints improve search accuracy by specifying what to look for:

```json
{
  "preferredDocumentTypes": ["CONTRACT", "POLICY", "GUIDELINES"],
  "countries": ["IT", "DE", "FR"],
  "clients": ["UniCredit", "Intesa Sanpaolo"],
  "sectors": ["Finance", "Insurance"],
  "contractTypes": ["Service Agreement", "NDA"],
  "keywords": ["payment", "delivery", "quality"]
}
```

Each hint increases score when matched, improving ranking.

### Chunking Strategy

- **Chunk Size**: 300 tokens (~1200 characters)
- **Overlap**: 50 tokens (preserves context across chunks)
- **Indexing**: One embedding per chunk
- **Metadata**: Full document metadata attached to each chunk

---

## EXPORT FORMATS

### JSON (Default)

**Format**: Native JSON response  
**File Generated**: No  
**Use Case**: API integrations, programmatic consumption

**Example**:
```json
{
  "status": "OK",
  "answer": "# Report\n\n...",
  "metadata": {...}
}
```

---

### CSV (Comma/Semicolon Separated)

**Format**: RFC 4180 (semicolon separator for Italian Excel)  
**File Generated**: `.csv` file  
**Use Case**: Data analysis in spreadsheets  
**File Size**: Depends on report length

**Characteristics**:
- Semicolon separator (`;`) instead of comma
- Double-quote escaping for special characters
- UTF-8 encoding
- One field contains full report as single cell

---

### XLSX (Excel Workbook)

**Format**: Office Open XML  
**File Generated**: `.xlsx` file  
**Use Case**: Professional business reports  

**Features**:
- Multiple sheets if complex data
- Professional styling:
  - Title formatting (bold, larger font)
  - Section headers (colored background)
  - Data tables (bordered cells)
  - Alternating row colors
- Auto-sized columns
- Frozen header rows
- Print-optimized layout

**Example Structure**:
```
Sheet 1: Report
├─ Title (merged cells, bold)
├─ Executive Summary
├─ Section 1
├─ Section 2
└─ Metadata (timestamp, quality score)

Sheet 2: Metadata (if applicable)
├─ Generated At
├─ Source Documents
├─ Quality Scores
└─ Execution Time
```

---

### DOCX (Word Document)

**Format**: Office Open XML  
**File Generated**: `.docx` file  
**Use Case**: Professional document distribution  

**Features**:
- Proper heading hierarchy (H1, H2, H3)
- Paragraph formatting
- Automatic table of contents support
- Numbered/bulleted lists preserved
- Image and media support
- Pagination aware
- Print-optimized

**Example Structure**:
```
DOCX File
├─ Document Properties
│  ├─ Title (from report)
│  ├─ Author: ReportAI
│  ├─ Created: Timestamp
│  └─ Subject: Auto-generated
│
├─ Content
│  ├─ Heading 1: Title
│  ├─ Heading 2: Sections
│  ├─ Paragraphs: Body text
│  ├─ Tables: Formatted data
│  └─ Lists: Formatted bullets
│
├─ Styles
│  ├─ Heading styles
│  ├─ Body text style
│  ├─ Table styles
│  └─ List styles
│
└─ Page Layout
   ├─ Margins: 1 inch
   ├─ Font: Calibri 11pt
   ├─ Line spacing: 1.15
   └─ Page breaks preserved
```

---

### MARKDOWN

**Format**: Markdown text  
**File Generated**: No (stays in JSON response)  
**Use Case**: Documentation, version control  

**Features**:
- Headings with `#`, `##`, `###`
- Lists with `*`, `-`, or numbers
- Code blocks with ` ``` `
- Bold/italic with `**`, `*`
- Links with `[text](url)`
- Blockquotes with `>`

**Example**:
```markdown
# Report Title

## Section 1
Description here

### Subsection 1.1
Details...

## Section 2
- Point 1
- Point 2
- Point 3

**Important**: Bold text here.
```

---

## QUALITY ASSURANCE

### Output Validation

**Purpose**: Detect hallucinations and quality issues in generated content

**Validation Checks**:

1. **Length Validation**
   - Minimum: 100 characters
   - Maximum: 50,000 characters
   - Flag: Detects suspiciously short or long outputs

2. **Hallucination Detection**
   - Checks for fabricated data indicators
   - Detects contradictions with source documents
   - Identifies unsupported claims
   - Flag: `halluccinationRiskDetected` (boolean)

3. **Structure Validation**
   - Checks for proper markdown structure
   - Validates heading hierarchy
   - Confirms content coherence
   - Detects formatting errors

4. **Quality Scoring**
   - Calculation based on:
     - Content relevance to prompt
     - Source document coverage
     - Logical flow and structure
     - Writing quality indicators
   - Score: 0.0 to 1.0
   - Threshold: 0.6 (60%) acceptable

5. **Context Availability**
   - Adds warning if NO context available
   - Reminds Claude to not fabricate
   - Marks as "NO_CONTEXT" case
   - Extra validation if critical

### Context Quality Scoring

**Purpose**: Measure quality of retrieved context before generation

**Metrics**:

1. **Similarity Average**
   - Mean cosine similarity of retrieved documents
   - Range: 0.0 to 1.0
   - Higher is better (0.7+ is excellent)

2. **Document Coverage**
   - How many relevant documents retrieved
   - Expected: 3-5 documents
   - Flag if 0 (no KB match)

3. **Metadata Match Score**
   - How well metadata hints were satisfied
   - Range: 0.0 to 1.0
   - Indicates hint quality

4. **Overall Score**
   - Combined metric (0.0 to 1.0)
   - Used for quality reporting
   - Helps identify problematic queries

5. **Risk Level**
   - LOW: All metrics excellent (score 0.8+)
   - MEDIUM: Some metrics acceptable (0.6-0.8)
   - HIGH: Poor metrics (score <0.6)
   - CRITICAL: No context (score <0.4)

### Automatic Regeneration

**Trigger**: If initial output has CRITICAL quality issues

**Process**:
1. First generation call completes
2. Quality validation runs
3. If critical issues detected:
   - Enhanced system prompt created
   - Warning added: "[PREVIOUS RESPONSE HAD QUALITY ISSUES - PLEASE REGENERATE]"
   - Claude called again
   - Result replaces original

**Outcome**:
- Higher quality output
- Same executionTime reflects both calls
- Metadata.validationMessage explains regeneration

---

## WEB SEARCH INTEGRATION

### Tavily Web Search

**Purpose**: Supplement knowledge base with external information when needed

**Activation Conditions**:
1. app.web-search.enabled=true (application.properties)
2. app.web-search.provider=TAVILY
3. API key configured (app.web-search.tavily.api-key)
4. allowWebSearch=true in request
5. Knowledge base has NO good results

**Configuration**:

```properties
app.web-search.enabled=true
app.web-search.provider=TAVILY
app.web-search.tavily.api-key=tvly-dev-xxxxx
app.web-search.cache-ttl-minutes=1440
```

### Search Process

```
1. Check if web search needed
   ├─ If KB found good results → Skip web search
   ├─ If KB empty AND web enabled → Execute web search
   └─ If KB empty AND web disabled → Proceed without

2. Call Tavily API
   ├─ Query: User prompt
   ├─ Max results: 5
   ├─ Search depth: advanced
   ├─ Include domains: true
   └─ Timeout: REST client default

3. Process Results
   ├─ Check HTTP 200 status
   ├─ Parse JSON response
   ├─ Extract result array
   └─ Format each result

4. Format Results
   ├─ Include title (bolded)
   ├─ Include relevance score
   ├─ Include content snippet (max 500 chars)
   ├─ Include source URL
   └─ Convert to Markdown format

5. Cache Results
   ├─ Store in Guava cache
   ├─ TTL: 1440 minutes (24 hours, configurable)
   ├─ Max size: 1000 queries
   └─ Track cache statistics

6. Return to Orchestrator
   ├─ List of formatted web results
   ├─ Include in context assembly
   ├─ Logged as webSearchUsed=true
   └─ Included in response metadata
```

### Caching Strategy

- **Key**: Original query string
- **Value**: List of formatted search results
- **TTL**: 1440 minutes (24 hours, configurable)
- **Max Size**: 1000 unique queries
- **Statistics**: Hit/miss tracking enabled

### Error Handling

- If API key not configured: Returns empty list (graceful degradation)
- If API connection error: Logs error, returns empty list
- If malformed response: Logs error, returns empty list
- If specific result malformed: Skips that result, continues

---

## TEMPLATE SYSTEM

### Template Definition

Templates allow standardized report formatting

**Fields**:
- ID: Unique identifier
- Name: Template display name
- Format: Target format (XLSX, DOCX, PDF, etc.)
- TemplateContent: Template structure (HTML/FreeMarker/Velocity)

### Template Variables

When rendering, available variables:

```velocity
$reportTitle - Title of the report
$reportContent - Generated content (Markdown)
$generatedAt - Timestamp of generation
$executionTime - Time taken (ms)
$qualityScore - Output quality (0-1)
$sourceDocuments - Number of KB documents used
$webSearchUsed - Boolean: was web search used
$requestPrompt - User's original prompt
```

### Example Template

```html
<html>
  <head>
    <title>$reportTitle</title>
  </head>
  <body>
    <h1>$reportTitle</h1>
    <p>Generated: $generatedAt</p>
    <div class="content">
      $reportContent
    </div>
    <footer>
      Quality: $qualityScore | Sources: $sourceDocuments | Time: ${executionTime}ms
    </footer>
  </body>
</html>
```

---

## ADVANCED FEATURES

### Request-Scoped Temporary Files

Each report generation request:

1. **Creates temp directory**
   - Unique directory per request
   - Location: `./data/temp/{request-id}/`

2. **Processes uploaded files**
   - Extracts text
   - Generates embeddings
   - Indexes temporarily
   - Combines with persistent KB

3. **Uses in report generation**
   - Temp embeddings search
   - Combined with KB search
   - Separate in metadata

4. **Cleanup**
   - After response generated
   - In finally block (guaranteed)
   - Releases resources
   - Prevents disk space issues

### Metadata Hints Extraction

Automatically extract hints from prompt:

```java
// Input: "I need an NDA for a German client in the Finance sector"
// Output hints:
{
  "countries": ["Germany", "DE"],
  "sectors": ["Finance"],
  "contractTypes": ["NDA"],
  "keywords": ["contract", "agreement"]
}
```

These hints improve document ranking in search.

### Intelligent Fallback Strategy

Priority order:

1. **Use knowledge base results**
   - If good similarity scores (0.7+)
   - If relevant documents found
   - Most reliable source

2. **Fallback to web search**
   - If KB empty or scores too low
   - If web search enabled
   - External validation

3. **Generate without context**
   - If both KB and web empty
   - With hallucination warning
   - Less reliable but still attempts

### Performance Optimizations

- **Caching**: Web search results cached 24h
- **Indexing**: Vector store indexed for fast retrieval
- **Batching**: Embeddings generated in batches
- **Streaming**: Large files streamed to disk
- **Connection pooling**: Database connection pool configured
- **Lazy loading**: Templates loaded on-demand

### Monitoring & Metrics

Every response includes:

```json
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
```

Use these metrics to:
- Monitor system performance
- Identify slow queries
- Track quality trends
- Detect anomalies
- Improve prompt design

