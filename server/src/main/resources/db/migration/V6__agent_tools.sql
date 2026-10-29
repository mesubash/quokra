-- The agent's display name from their last sign-in, so others can pick them when transferring a chat.
alter table tenant_agents add column name text;

-- Notes agents leave on a conversation. Never shown to the customer.
create table conversation_notes (
    id               uuid primary key,
    conversation_id  uuid not null references conversations,
    author_sub       text not null,
    author_name      text,
    body             text not null,
    created_at       timestamptz not null
);

create index conversation_notes_by_conversation on conversation_notes (conversation_id, id);
