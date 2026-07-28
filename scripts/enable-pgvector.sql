-- Run this only after pgvector has been installed on the PostgreSQL server.
-- Existing text embeddings must contain valid vector literals such as: [0.1,0.2,...]

CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE embedding_records
    ALTER COLUMN embedding TYPE vector(1536)
    USING NULLIF(embedding, '')::vector;

CREATE INDEX IF NOT EXISTS ix_embedding_records_cosine
    ON embedding_records
    USING hnsw (embedding vector_cosine_ops);
