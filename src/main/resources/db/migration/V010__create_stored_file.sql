CREATE TABLE stored_file (
    id UUID PRIMARY KEY,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(255),
    sha256 VARCHAR(64) NOT NULL UNIQUE,
    size_bytes BIGINT NOT NULL,
    storage_path VARCHAR(255) NOT NULL,
    extracted_text TEXT,
    extraction_status VARCHAR(255) NOT NULL,
    metadata_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);