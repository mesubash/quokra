-- Which agents (by their identity provider subject) work on which tenant.
create table tenant_agents (
    tenant_id  uuid not null references tenants,
    agent_sub  text not null,
    primary key (tenant_id, agent_sub)
);

create index tenant_agents_by_agent on tenant_agents (agent_sub);

-- When the conversation entered the queue; cleared once someone takes it.
alter table conversations add column waiting_since timestamptz;

-- Shown in the agent's timeline.
alter table conversation_events add column actor_name text;
