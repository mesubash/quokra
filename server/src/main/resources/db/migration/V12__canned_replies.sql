-- Saved answers agents insert into a reply. {name} becomes the customer's name.
create table canned_replies (
    id          uuid primary key,
    tenant_id   uuid not null references tenants,
    title       text not null,
    body        text not null,
    created_at  timestamptz not null default now()
);

create index canned_replies_by_tenant on canned_replies (tenant_id, lower(title));
