-- How far each agent has read each conversation, for the unread counts in the console.
create table conversation_reads (
    conversation_id       uuid not null references conversations,
    agent_sub             text not null,
    last_read_message_id  uuid not null,
    primary key (conversation_id, agent_sub)
);
