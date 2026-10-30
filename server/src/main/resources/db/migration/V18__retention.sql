-- How long resolved chats are kept, in months (0 = forever). The server's own limits apply on top
-- (quokra.retention.min-months / max-months), see RetentionJob.
alter table tenants add column retention_months int not null default 12 check (retention_months >= 0);
