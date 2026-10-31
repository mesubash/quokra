-- Company admins: they manage their own company's settings, bot, agents and keys, but not other
-- companies or the server. Server admins (the admin role in the token) still manage everything.
alter table tenant_agents add column admin boolean not null default false;
alter table agent_invites add column admin boolean not null default false;
