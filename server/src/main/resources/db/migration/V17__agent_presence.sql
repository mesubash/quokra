-- Whether an agent is answering chats. An open console checks in every minute; an agent counts as online
-- while their status is ONLINE and they checked in within the last two minutes.
create table agent_presence (
    agent_sub  text primary key,
    status     text not null default 'ONLINE' check (status in ('ONLINE', 'AWAY')),
    last_seen  timestamptz not null
);
