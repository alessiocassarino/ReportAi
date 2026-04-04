CREATE TABLE vectore_upload (
    id UUID PRIMARY KEY,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    original_filename VARCHAR(255) NOT NULL,
    stored_file_id UUID,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT chk_vectore_upload_status
        CHECK (status IN (
            'PENDING',
            'PROCESSING',
            'COMPLETED',
            'FAILED',
            'ALREADY_EXISTS',
            'NO_TEXT'
        ))
);