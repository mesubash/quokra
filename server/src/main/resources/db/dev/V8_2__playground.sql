-- Dev only: the playground dashboard (Vite on localhost:5173) embeds the demo tenant's chat and console,
-- and a second demo agent lets you test transfers between agents.
update tenants
set widget_origins = array_append(widget_origins, 'http://localhost:5173'),
    console_origins = array_append(console_origins, 'http://localhost:5173')
where slug = 'demo';

update tenant_agents set name = 'Sam' where agent_sub = 'dev-agent';

insert into tenant_agents (tenant_id, agent_sub, name)
select id, 'dev-agent-2', 'Alex' from tenants where slug = 'demo';
