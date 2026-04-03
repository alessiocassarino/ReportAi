-- ─────────────────────────────────────────────────────────────
-- Init Script per PostgreSQL - Eseguito al primo avvio
-- ─────────────────────────────────────────────────────────────

-- Crea l'estensione pgvector se non esiste già
CREATE EXTENSION IF NOT EXISTS vector;

-- Il resto delle tabelle sarà creato da Liquibase all'avvio di Spring Boot
