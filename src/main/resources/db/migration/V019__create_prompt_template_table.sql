-- V019: Prompt template table for multi-sector LLM prompt management.
-- Prompt text is stored in the DB so it can be changed without code redeploy.

CREATE TABLE prompt_template (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    template_key  VARCHAR(200) NOT NULL,
    workflow_id   VARCHAR(100) NOT NULL,
    sector        VARCHAR(50)  NOT NULL,
    version       VARCHAR(20)  NOT NULL DEFAULT 'v1',
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    role          VARCHAR(10)  NOT NULL DEFAULT 'SYSTEM',
    prompt_name   VARCHAR(200),
    body          TEXT         NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_prompt_key_sector_version UNIQUE (template_key, sector, version)
);

CREATE INDEX idx_prompt_key_sector ON prompt_template (template_key, sector, active);
