-- Aggiunge campi di debug alla tabella price_comparison per tracciare
-- i JSON intermedi della pipeline LLM e diagnosticare errori di estrazione/merge.
ALTER TABLE price_comparison
    ADD COLUMN IF NOT EXISTS debug_supplier_jsons TEXT,
    ADD COLUMN IF NOT EXISTS debug_comparison_json TEXT;
