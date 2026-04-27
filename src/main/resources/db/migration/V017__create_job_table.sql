-- V017: Unified job table for the v2 pipeline engine.
-- Replaces the three separate job tables at the API level (legacy tables are kept for processing).

CREATE TABLE job (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id     VARCHAR(100)  NOT NULL,
    sector          VARCHAR(50)   NOT NULL DEFAULT 'OIL_GAS',
    status          VARCHAR(20)   NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED','CANCELLED')),
    progress        INT           NOT NULL DEFAULT 0,
    current_step    TEXT,
    error_message   TEXT,
    model           VARCHAR(100),
    input_filenames TEXT,
    num_files       INT           NOT NULL DEFAULT 1,
    legacy_job_id   VARCHAR(36),
    legacy_type     VARCHAR(30),
    created_by      UUID,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_job_status      ON job (status);
CREATE INDEX idx_job_workflow_id ON job (workflow_id);
CREATE INDEX idx_job_created_by  ON job (created_by);
CREATE INDEX idx_job_created_at  ON job (created_at DESC);
