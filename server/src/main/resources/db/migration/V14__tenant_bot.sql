-- A tenant's own bot, edited in the Admin tab: the same fields as a bot file, as JSON.
-- Null means the tenant uses its bot file (bot_config).
alter table tenants add column bot_custom text;
