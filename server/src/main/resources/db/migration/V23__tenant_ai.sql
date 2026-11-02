-- v3: whether a company uses AI (suggested replies for agents). Off until its admin turns it on, since it
-- sends customers' messages to the AI provider.
alter table tenants add column ai_enabled boolean not null default false;
