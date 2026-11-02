-- v3 step 3b: the AI answers customers' typed questions from the company's documents, while the bot has the
-- chat. Off until the company's admin turns it on. sources marks a message as an AI answer: the document
-- parts it came from, as JSON [{ "title": …, "heading": … }].
alter table tenants add column ai_answers_enabled boolean not null default false;
alter table messages add column sources text;
