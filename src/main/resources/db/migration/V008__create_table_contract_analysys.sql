CREATE TABLE contract_analysis (
    id UUID PRIMARY KEY,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    progress INTEGER NOT NULL DEFAULT 0,
    current_step TEXT,
    error_message TEXT,
    result_file_name VARCHAR(255),
    result_file_content BYTEA,
    model VARCHAR(255) NOT NULL DEFAULT 'claude-haiku-4-5-20251001',
    original_filename VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT chk_contract_analysis_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);