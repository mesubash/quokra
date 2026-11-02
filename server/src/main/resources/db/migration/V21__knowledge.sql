-- v3: a company's own documents the AI answers from. Chunks are the parts that search and answers use;
-- they're rebuilt whenever a document changes. Vectors come with search (pgvector).
create table knowledge_documents (
    id          uuid primary key,
    tenant_id   uuid not null references tenants on delete cascade,
    title       text not null,
    body        text not null,
    updated_by  text,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create index knowledge_documents_by_tenant on knowledge_documents (tenant_id, lower(title));

create table knowledge_chunks (
    document_id uuid not null references knowledge_documents on delete cascade,
    position    int not null,
    -- The headings above this part, e.g. "Shipping › Returns"; null before the first heading.
    heading     text,
    body        text not null,
    primary key (document_id, position)
);
