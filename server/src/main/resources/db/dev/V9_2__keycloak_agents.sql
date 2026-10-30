-- Dev only: the agents from the local Keycloak realm (server/keycloak/quokra-realm.json) work for the demo tenant.
insert into tenant_agents (tenant_id, agent_sub, name)
select id, '0b9e8a3c-0001-4000-8000-00000000a001', 'Sam Lee' from tenants where slug = 'demo'
union all
select id, '0b9e8a3c-0001-4000-8000-00000000a002', 'Alex Kim' from tenants where slug = 'demo';
