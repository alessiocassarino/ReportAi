-- V018: Add sector column to legacy job tables.
-- Defaults to OIL_GAS for all existing rows (backward compatible).
-- Used by processors to load the correct prompt template.

ALTER TABLE contract_analysis  ADD COLUMN IF NOT EXISTS sector VARCHAR(50) NOT NULL DEFAULT 'OIL_GAS';
ALTER TABLE estimate           ADD COLUMN IF NOT EXISTS sector VARCHAR(50) NOT NULL DEFAULT 'OIL_GAS';
ALTER TABLE price_comparison   ADD COLUMN IF NOT EXISTS sector VARCHAR(50) NOT NULL DEFAULT 'OIL_GAS';
