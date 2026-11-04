-- v3: AI calls per company per day (UTC), for the daily limits. The server's total is the sum of a day.
create table ai_usage (
    tenant_id uuid not null references tenants (id) on delete cascade,
    day       date not null,
    calls     integer not null,
    primary key (tenant_id, day)
);

create index ai_usage_day on ai_usage (day);
