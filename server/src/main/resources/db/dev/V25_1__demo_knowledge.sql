-- Dev and demo only: a sample knowledge document, so the demo company's AI has something to answer from
-- (only once the server has QUOKRA_AI_KEY). Its parts match what KnowledgeChunker makes; the embedder adds
-- the vectors within a minute of starting.
insert into knowledge_documents (id, tenant_id, title, body, updated_by)
select '0192f000-0000-7000-8000-00000000d0c1', id, 'Quokra handbook',
       '## Running Quokra

Quokra runs on your own server as one Docker image, ghcr.io/mesubash/quokra, with PostgreSQL 17 and the pgvector extension. A small server is enough to start, and there''s a guide for a free public demo on Render, Neon and Vercel.

Agents sign in through your own identity provider (OIDC), for example Keycloak, Auth0, Okta or Entra ID. There''s no separate Quokra account to create.

## Customers and their data

Guests can chat without logging in. Before they talk to a person, the bot asks for an email and, optionally, a name.

Logged-in customers are identified with a short-lived token signed by your backend, so they keep one chat across devices. Resolved chats are deleted after the company''s retention period, 12 months unless an admin changes it.

## Images

Customers and agents can send PNG, JPEG, GIF or WebP images up to 5 MB, once an admin allows images for the company. They''re stored on the server''s disk or in S3-compatible storage.

## Opening hours

Each company can set opening hours per day, in its own time zone, including hours past midnight. Outside them the bot still answers and chats still reach the queue, but the customer is told the team is away and when it''s back.

## Notifications

Agents get sounds, toasts and browser notifications in the console. With an SMTP server, the team is emailed when a chat waits with nobody online, and customers can get replies they missed by email.

## AI answers

AI is optional and off by default for each company. Admins add documents in Settings, under Knowledge. Agents can then ask for a suggested reply, and the bot can answer customers itself.

Answers come only from those documents, are marked as AI and show their sources. When there''s no good answer, the chat goes to a person. It works with any OpenAI-compatible provider, such as Gemini, OpenAI or a model you run yourself, and has daily cost limits.

## Languages

All of the chat''s wording can be changed per company in Settings, so the chat can be written in any language. The agent console is in English.',
       'seed'
from tenants where slug = 'demo';

insert into knowledge_chunks (document_id, position, heading, body)
select '0192f000-0000-7000-8000-00000000d0c1', v.position, v.heading, v.body
from (values
    (0, 'Running Quokra', 'Quokra runs on your own server as one Docker image, ghcr.io/mesubash/quokra, with PostgreSQL 17 and the pgvector extension. A small server is enough to start, and there''s a guide for a free public demo on Render, Neon and Vercel.

Agents sign in through your own identity provider (OIDC), for example Keycloak, Auth0, Okta or Entra ID. There''s no separate Quokra account to create.'),
    (1, 'Customers and their data', 'Guests can chat without logging in. Before they talk to a person, the bot asks for an email and, optionally, a name.

Logged-in customers are identified with a short-lived token signed by your backend, so they keep one chat across devices. Resolved chats are deleted after the company''s retention period, 12 months unless an admin changes it.'),
    (2, 'Images', 'Customers and agents can send PNG, JPEG, GIF or WebP images up to 5 MB, once an admin allows images for the company. They''re stored on the server''s disk or in S3-compatible storage.'),
    (3, 'Opening hours', 'Each company can set opening hours per day, in its own time zone, including hours past midnight. Outside them the bot still answers and chats still reach the queue, but the customer is told the team is away and when it''s back.'),
    (4, 'Notifications', 'Agents get sounds, toasts and browser notifications in the console. With an SMTP server, the team is emailed when a chat waits with nobody online, and customers can get replies they missed by email.'),
    (5, 'AI answers', 'AI is optional and off by default for each company. Admins add documents in Settings, under Knowledge. Agents can then ask for a suggested reply, and the bot can answer customers itself.

Answers come only from those documents, are marked as AI and show their sources. When there''s no good answer, the chat goes to a person. It works with any OpenAI-compatible provider, such as Gemini, OpenAI or a model you run yourself, and has daily cost limits.'),
    (6, 'Languages', 'All of the chat''s wording can be changed per company in Settings, so the chat can be written in any language. The agent console is in English.')
) as v (position, heading, body)
where exists (select 1 from knowledge_documents where id = '0192f000-0000-7000-8000-00000000d0c1');
