-- The agent's verified email from their last sign-in, so an admin can add a known agent by email.
alter table tenant_agents add column email text;
create index tenant_agents_by_email on tenant_agents (lower(email));

-- Agents added by email who haven't signed in yet. On their first sign-in with that verified email,
-- the invite becomes a tenant_agents row.
create table agent_invites (
    tenant_id   uuid not null references tenants,
    email       text not null,
    invited_by  text,
    created_at  timestamptz not null default now()
);

create unique index agent_invites_email on agent_invites (tenant_id, lower(email));
