-- The customer's thumbs up or down after a chat with an agent, with an optional comment. One per conversation,
-- deleted with it. agent_sub / agent_name: who had the chat when it was rated.
create table chat_ratings (
    conversation_id  uuid primary key references conversations (id) on delete cascade,
    tenant_id        uuid not null references tenants,
    agent_sub        text not null,
    agent_name       text,
    positive         boolean not null,
    comment          text check (length(comment) <= 1000),
    created_at       timestamptz not null default now()
);

create index chat_ratings_by_tenant on chat_ratings (tenant_id, created_at);
