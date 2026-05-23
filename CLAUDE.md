# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

### Run the full application
```bash
docker compose up --build -d   # First run (builds Docker images)
docker compose up -d            # Subsequent runs
docker compose down             # Stop
docker compose down -v          # Stop and remove volumes (full reset)
docker compose logs -f spring-boot  # Stream backend logs
```

### Build backend only (Maven)
```bash
./mvnw clean package -DskipTests
```

### Frontend
The React bundle is pre-compiled and lives in `frontend/dist/`. There is no frontend build step — Nginx serves the static files directly.

### Tests
There are currently no tests in `src/test/`.

---

## Architecture

**Stack:** Java 21 + Spring Boot 3.5, React (pre-compiled), PostgreSQL 16 + pgvector, Nginx, Ollama.

**External APIs:**
- Anthropic Claude (mandatory) — models: `claude-haiku-4-5`, `claude-sonnet-4-5/4-6`, `claude-opus-4-6/4-7`
- Tavily Web Search (mandatory for estimates)
- Google Cloud Vertex AI / Gemini (optional, user-selectable)
- Frankfurter (free, EUR/USD exchange rate)
- Ollama local server — `mxbai-embed-large` model for RAG embeddings

**Docker containers:**
- `postgres` — port 5400:5432, pgvector extension
- `ollama` — port 11434 (internal), loads `mxbai-embed-large`
- `spring-boot` — port 8080
- `nginx` — port 80, serves React + reverse proxies `/api/*` to Spring Boot

**Request flow:**
```
Browser → Nginx :80 → (static)  frontend/dist/
                     → /api/*   Spring Boot :8080
                                     ↓
                              PostgreSQL (JPA + pgvector)
                              Ollama (embeddings)
                              Anthropic / Gemini APIs
                              Tavily API
```

---

## Source Structure

```
src/main/java/com/claude/reportAi/
├── configuration/          Spring Security, JWT filter, CORS, AI tool beans
├── controller/             REST endpoints (Auth, Contract, Estimate, PriceComparison,
│                           StoredFile/RAG, History, Models, UserAdmin)
├── service/
│   ├── ContractAnalysis*   PDF → section extraction → parallel AI risk analysis → Word report
│   ├── estimate/           PDF spec extraction → RAG lookup → Tavily web search → Word report
│   ├── pricecomparison/    Supplier offer comparison → recommendation report
│   ├── rag/                pgvector ingestion & retrieval
│   ├── ModelChatClientFactory  Routes to Claude or Gemini based on user selection
│   ├── AuthenticationService   JWT issue/validate
│   ├── ExchangeRateService     EUR/USD via Frankfurter (cached)
│   └── TokenRateLimiter
├── entities/               JPA entities (User, Role, Contract, Estimate, PriceComparison, StoredFile)
├── repository/             Spring Data JPA interfaces
├── dto/                    Request/response DTOs
├── exception/              Custom exceptions
├── observability/          Logging/metrics hooks
└── util/                   Helpers (PDF image extraction, JSON repair, etc.)

src/main/resources/
├── application.properties  All tunable config (models, pricing, JWT, CORS, file limits…)
└── db/migration/           Flyway SQL migrations V001–V016
```

---

## Key Design Patterns

**AI model routing** — `ModelChatClientFactory` selects Claude or Gemini at runtime based on a per-request model ID. Each service (Contract, Estimate, PriceComparison) independently chooses which model tier to use (Haiku = speed, Sonnet = balanced, Opus = quality).

**RAG pipeline** — uploaded documents (PDF, Word, Excel, CSV, TXT, HTML, RTF, max 50 MB) are chunked, vectorized via Ollama, and stored in pgvector. At query time, relevant chunks are retrieved and injected into the AI prompt.

**Async estimate flow** — `EstimateService` runs long jobs in background threads; controllers poll status via a job-ID endpoint.

**Prompt engineering** — System prompts use XML tags, explicit steps, and examples. All prompts are in Italian. See `docs/AI_PROMPTS_REFERENCE.md` for the full reference.

**Schema migrations** — Flyway manages the schema. Never edit existing migration files; always add a new `V0NN__description.sql`.

---

## Configuration

`.env` must exist at the project root (see `.env.example`). Key variables:

```
ANTHROPIC_API_KEY
TAVILY_API_KEY
JWT_SECRET
SPRING_DATASOURCE_URL / USERNAME / PASSWORD
GOOGLE_CLOUD_PROJECT_ID / LOCATION / APPLICATION_CREDENTIALS_PATH
SPRING_AI_OLLAMA_BASE_URL
```

Model IDs, token pricing, CORS origins, file-upload limits, PDF DPI/quality, and report branding are all set in `application.properties`.

---

## Roles & Access

Three roles: `ADMIN`, `ANALYST`, `USER`.  
- PriceComparison endpoints are ADMIN-only.  
- JWT access tokens expire in 15 min; refresh tokens in 7 days.

---

## Reports

Word (`.docx`) reports are generated with Apache POI. The company logo is read from `data/logo.png`. Report branding (company name, logo path) is configured in `application.properties`.
