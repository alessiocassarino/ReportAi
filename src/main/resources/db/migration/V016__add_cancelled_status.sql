-- Aggiunge lo stato CANCELLED a tutte le tabelle job.
-- CANCELLED è uno stato terminale che indica annullamento volontario da parte dell'utente.

ALTER TABLE estimate DROP CONSTRAINT chk_estimate_status;
ALTER TABLE estimate ADD CONSTRAINT chk_estimate_status
    CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED'));

ALTER TABLE contract_analysis DROP CONSTRAINT chk_contract_analysis_status;
ALTER TABLE contract_analysis ADD CONSTRAINT chk_contract_analysis_status
    CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED'));

ALTER TABLE price_comparison DROP CONSTRAINT chk_price_comparison_status;
ALTER TABLE price_comparison ADD CONSTRAINT chk_price_comparison_status
    CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED'));

ALTER TABLE vectore_upload DROP CONSTRAINT chk_vectore_upload_status;
ALTER TABLE vectore_upload ADD CONSTRAINT chk_vectore_upload_status
    CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED', 'ALREADY_EXISTS', 'NO_TEXT'));
