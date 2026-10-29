-- Local agent for the demo tenant (dev profile only). Get a token from POST /dev/agent-token.
insert into tenant_agents (tenant_id, agent_sub) values ('00000000-0000-0000-0000-000000000001', 'dev-agent');
