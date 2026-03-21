-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Create vector store table
CREATE TABLE IF NOT EXISTS vector_store (
    id BIGSERIAL PRIMARY KEY,
    metadata JSONB,
    embedding vector(1024),
    content TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Create indices for performance
CREATE INDEX IF NOT EXISTS vector_store_embedding_idx ON vector_store USING ivfflat (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS vector_store_metadata_idx ON vector_store USING GIN(metadata);
