-- Dev only: the demo sign-in agents (Sam and Alex) are company admins of the demo tenant, so a public
-- demo can show Settings without making visitors server admins. The Keycloak agents stay plain agents.
update tenant_agents set admin = true
where agent_sub in ('dev-agent', 'dev-agent-2')
  and tenant_id = (select id from tenants where slug = 'demo');
