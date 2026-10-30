-- Close quiet conversations by themselves after this many minutes without messages (0 = never).
alter table tenants add column auto_resolve_minutes int not null default 1440 check (auto_resolve_minutes >= 0);
