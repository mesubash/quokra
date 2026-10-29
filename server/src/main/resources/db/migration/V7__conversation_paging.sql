-- The queue's sort order as a number (urgent first), so pages can continue from the last row they showed.
alter table conversations add column priority_rank int generated always as (
    case priority when 'URGENT' then 0 when 'HIGH' then 1 else 2 end) stored;

drop index conversations_queue;
create index conversations_queue on conversations (tenant_id, status, priority_rank, waiting_since, id);
create index conversations_recent on conversations (tenant_id, status, last_message_at desc, id desc);
