-- v3 search: each chunk's embedding, and the model that made it, so changing the model embeds again.
-- Needs PostgreSQL with pgvector (Neon has it; Docker: pgvector/pgvector:pg17).
create extension if not exists vector;

alter table knowledge_chunks add column embedding vector(768);
alter table knowledge_chunks add column embedding_model text;

create index knowledge_chunks_embedding on knowledge_chunks using hnsw (embedding vector_cosine_ops);
-- The embedder looks for chunks still waiting for one.
create index knowledge_chunks_waiting on knowledge_chunks (document_id) where embedding is null;
