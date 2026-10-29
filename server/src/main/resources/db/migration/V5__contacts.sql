-- People who left their details in the chat. One per email per tenant, so a returning
-- customer's conversations end up together.
create table contacts (
    id          uuid primary key,
    tenant_id   uuid not null references tenants,
    name        text,
    email       text not null,
    blocked_at  timestamptz,
    blocked_by  text,
    created_at  timestamptz not null,
    updated_at  timestamptz not null
);

create unique index contacts_email on contacts (tenant_id, lower(email));

alter table conversations add column contact_id uuid references contacts;
create index conversations_by_contact on conversations (contact_id, created_at);

-- Set when the customer asked for a person but hasn't left their details yet.
alter table conversations add column pending_handoff text;
