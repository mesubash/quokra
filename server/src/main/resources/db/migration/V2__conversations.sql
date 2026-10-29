create table conversations (
    id               uuid primary key,
    tenant_id        uuid not null references tenants,
    status           text not null,
    visitor_id       uuid not null,
    customer_sub     text,
    customer_name    text,
    customer_email   text,
    channel          text not null default 'WEB',
    assignee_sub     text,
    assignee_name    text,
    priority         text not null default 'NORMAL',
    handoff_reason   text,
    fallback_count   int  not null default 0,
    version          int  not null default 0,
    created_at       timestamptz not null,
    updated_at       timestamptz not null,
    last_message_at  timestamptz not null,
    resolved_at      timestamptz
);

create index conversations_queue on conversations (tenant_id, status, priority, last_message_at);
create index conversations_visitor on conversations (tenant_id, visitor_id, created_at);

-- One open conversation per visitor, and per identified customer.
create unique index one_open_conversation_per_visitor
    on conversations (tenant_id, visitor_id) where status <> 'RESOLVED';
create unique index one_open_conversation_per_customer
    on conversations (tenant_id, customer_sub) where status <> 'RESOLVED' and customer_sub is not null;

create table messages (
    id                 uuid primary key,
    conversation_id    uuid not null references conversations,
    sender_type        text not null,
    sender_sub         text,
    sender_name        text,
    body               text not null,
    client_message_id  text,
    created_at         timestamptz not null,
    unique (conversation_id, client_message_id)
);

create index messages_by_conversation on messages (conversation_id, id);

create table conversation_events (
    id               uuid primary key,
    conversation_id  uuid not null references conversations,
    type             text not null,
    actor_type       text not null,
    actor_sub        text,
    reason           text,
    created_at       timestamptz not null
);

create index conversation_events_by_conversation on conversation_events (conversation_id, created_at);
