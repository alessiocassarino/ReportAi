-- V023: Add sector field to stored_file and vectore_upload tables.
-- Backfill existing vector_store chunk metadata with sector = 'OIL_GAS'
-- so that the sector-scoped FilterExpression in InternalPricingRetriever works
-- for all pre-existing documents.

ALTER TABLE stored_file     ADD COLUMN IF NOT EXISTS sector VARCHAR(50) NULL;
ALTER TABLE vectore_upload  ADD COLUMN IF NOT EXISTS sector VARCHAR(50) NULL;

-- All chunks already in the vector store were uploaded before sector tagging existed
-- and belong to the OIL_GAS knowledge base, so we backfill them with that value.
-- metadata is VARCHAR(2000) containing JSON text, so we cast to jsonb, merge, cast back.
UPDATE vector_store
SET metadata = (metadata::jsonb || '{"sector": "OIL_GAS"}'::jsonb)::text
WHERE metadata IS NOT NULL
  AND metadata NOT LIKE '%"sector"%';
