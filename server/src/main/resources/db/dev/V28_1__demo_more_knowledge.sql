-- Dev and demo only: more sample documents about Quokra, so the demo's AI can answer most questions about it
-- (once the server has QUOKRA_AI_KEY). Parts match what KnowledgeChunker makes; the embedder adds the vectors.

insert into knowledge_documents (id, tenant_id, title, body, updated_by)
select '0192f000-0000-7000-8000-00000000d0c2', id, 'Adding Quokra to a site',
       '## The chat

The customer chat goes on any page with one script tag: sdk.js with the company''s slug and publishable key. It works with plain HTML, React, Next.js, Vue or anything else, with no build step on your side.

The chat follows the page''s light or dark mode by itself, uses the company''s colour and logo, and can sit on the left or the right.

## Placements

There are three ways to show the chat: a floating bubble in the corner, inline inside the page (for example on a help page), or a popup opened from your own button. A short greeting card can appear next to the bubble after a few seconds, or never.

## The agent console

The team''s console goes into your own dashboard with Quokra.console.mount, as a full Support page, or as a drawer opened from a floating button on every page. Agents sign in with the dashboard''s existing login, so there''s no separate Quokra password.

## Logged-in customers

When customers are logged in on your site, your backend signs a short-lived token (HS256, at most 5 minutes) with the company''s secret key, and the page passes it to Quokra.chat.identify. Agents then see who they''re talking to, and the customer keeps one chat across devices. Quokra.chat.logout() forgets them again.

## JavaScript API

The page can open and close the chat, change its theme, and listen for events such as unread messages, or unavailable when the chat can''t load, so the site can show its own contact options instead. Everything else is a documented REST and WebSocket API.',
       'seed'
from tenants where slug = 'demo';

insert into knowledge_chunks (document_id, position, heading, body)
select '0192f000-0000-7000-8000-00000000d0c2', v.position, v.heading, v.body
from (values
    (0, 'The chat', 'The customer chat goes on any page with one script tag: sdk.js with the company''s slug and publishable key. It works with plain HTML, React, Next.js, Vue or anything else, with no build step on your side.

The chat follows the page''s light or dark mode by itself, uses the company''s colour and logo, and can sit on the left or the right.'),
    (1, 'Placements', 'There are three ways to show the chat: a floating bubble in the corner, inline inside the page (for example on a help page), or a popup opened from your own button. A short greeting card can appear next to the bubble after a few seconds, or never.'),
    (2, 'The agent console', 'The team''s console goes into your own dashboard with Quokra.console.mount, as a full Support page, or as a drawer opened from a floating button on every page. Agents sign in with the dashboard''s existing login, so there''s no separate Quokra password.'),
    (3, 'Logged-in customers', 'When customers are logged in on your site, your backend signs a short-lived token (HS256, at most 5 minutes) with the company''s secret key, and the page passes it to Quokra.chat.identify. Agents then see who they''re talking to, and the customer keeps one chat across devices. Quokra.chat.logout() forgets them again.'),
    (4, 'JavaScript API', 'The page can open and close the chat, change its theme, and listen for events such as unread messages, or unavailable when the chat can''t load, so the site can show its own contact options instead. Everything else is a documented REST and WebSocket API.')
) as v (position, heading, body)
where exists (select 1 from knowledge_documents where id = '0192f000-0000-7000-8000-00000000d0c2');

insert into knowledge_documents (id, tenant_id, title, body, updated_by)
select '0192f000-0000-7000-8000-00000000d0c3', id, 'The agent console',
       '## Lists and search

The console has a queue of waiting chats, the agent''s own chats and resolved chats, each with unread counts, and updates live. Search finds chats by customer name, email or message text, and marks the matches.

## Answering a chat

Replying to a waiting chat assigns it to that agent. Agents can reply to a particular message, send images, see when the customer is typing, and use saved replies by typing / in the reply box. When they''re done they resolve the chat; it can be reopened later.

## Notes and transfers

Private notes are only seen by the team, never by the customer. A chat can be transferred to a colleague or back to the queue, or returned to the bot. Abusive contacts can be blocked.

## Keyboard shortcuts

The console has keyboard shortcuts for moving through the list, taking a chat and jumping to the reply box. Pressing ? shows them all.

## Notifications

Agents get a sound, a toast and, if they allow it, a browser notification for new waiting chats, replies in their chats, transfers and notes. The volume can be set in the bell menu. With an SMTP server, the team is also emailed when a chat waits with nobody online.

## Online and away

Each agent is Online or Away. Customers see the team as online only while someone is, and are told when to expect a reply otherwise. Outside the company''s opening hours the chat says when the team is back.

## Ratings

After a chat is resolved, the customer can rate it with a thumb up or down and an optional comment. Agents see the rating on the chat, and admins get a summary per agent in Settings, under Ratings.',
       'seed'
from tenants where slug = 'demo';

insert into knowledge_chunks (document_id, position, heading, body)
select '0192f000-0000-7000-8000-00000000d0c3', v.position, v.heading, v.body
from (values
    (0, 'Lists and search', 'The console has a queue of waiting chats, the agent''s own chats and resolved chats, each with unread counts, and updates live. Search finds chats by customer name, email or message text, and marks the matches.'),
    (1, 'Answering a chat', 'Replying to a waiting chat assigns it to that agent. Agents can reply to a particular message, send images, see when the customer is typing, and use saved replies by typing / in the reply box. When they''re done they resolve the chat; it can be reopened later.'),
    (2, 'Notes and transfers', 'Private notes are only seen by the team, never by the customer. A chat can be transferred to a colleague or back to the queue, or returned to the bot. Abusive contacts can be blocked.'),
    (3, 'Keyboard shortcuts', 'The console has keyboard shortcuts for moving through the list, taking a chat and jumping to the reply box. Pressing ? shows them all.'),
    (4, 'Notifications', 'Agents get a sound, a toast and, if they allow it, a browser notification for new waiting chats, replies in their chats, transfers and notes. The volume can be set in the bell menu. With an SMTP server, the team is also emailed when a chat waits with nobody online.'),
    (5, 'Online and away', 'Each agent is Online or Away. Customers see the team as online only while someone is, and are told when to expect a reply otherwise. Outside the company''s opening hours the chat says when the team is back.'),
    (6, 'Ratings', 'After a chat is resolved, the customer can rate it with a thumb up or down and an optional comment. Agents see the rating on the chat, and admins get a summary per agent in Settings, under Ratings.')
) as v (position, heading, body)
where exists (select 1 from knowledge_documents where id = '0192f000-0000-7000-8000-00000000d0c3');

insert into knowledge_documents (id, tenant_id, title, body, updated_by)
select '0192f000-0000-7000-8000-00000000d0c4', id, 'Writing the bot',
       '## Topics and keywords

The bot has a greeting and a menu of topics. Each topic has a title, an answer and keywords. When a customer types, the topic whose keywords match the most whole words answers; a tap on a button picks its topic directly.

## Urgent words

Urgent words such as outage send the chat straight to the team, marked urgent, so nobody waits on the bot when something is broken.

## Follow-up questions

A topic can offer follow-up questions as buttons after its answer, followed by Main topics and Talk to a person. A topic can also stay out of the main menu and still be found by its keywords. Bot answers can contain links with short labels.

## When the bot doesn''t understand

If nothing matches, the bot says so and shows the menu again. After two misses it hands the chat to the team. With AI answers on, the bot first tries to answer from the company''s documents before counting a miss.

## Buttons only

A bot can be set to buttons only, so customers pick topics instead of typing while the bot answers. They can still type once the chat is with a person.

## Editing

Admins edit the bot, its wording and every text the chat shows in Settings, under Bot, and changes apply at once without a restart. The bot can also be kept in a YAML file instead.',
       'seed'
from tenants where slug = 'demo';

insert into knowledge_chunks (document_id, position, heading, body)
select '0192f000-0000-7000-8000-00000000d0c4', v.position, v.heading, v.body
from (values
    (0, 'Topics and keywords', 'The bot has a greeting and a menu of topics. Each topic has a title, an answer and keywords. When a customer types, the topic whose keywords match the most whole words answers; a tap on a button picks its topic directly.'),
    (1, 'Urgent words', 'Urgent words such as outage send the chat straight to the team, marked urgent, so nobody waits on the bot when something is broken.'),
    (2, 'Follow-up questions', 'A topic can offer follow-up questions as buttons after its answer, followed by Main topics and Talk to a person. A topic can also stay out of the main menu and still be found by its keywords. Bot answers can contain links with short labels.'),
    (3, 'When the bot doesn''t understand', 'If nothing matches, the bot says so and shows the menu again. After two misses it hands the chat to the team. With AI answers on, the bot first tries to answer from the company''s documents before counting a miss.'),
    (4, 'Buttons only', 'A bot can be set to buttons only, so customers pick topics instead of typing while the bot answers. They can still type once the chat is with a person.'),
    (5, 'Editing', 'Admins edit the bot, its wording and every text the chat shows in Settings, under Bot, and changes apply at once without a restart. The bot can also be kept in a YAML file instead.')
) as v (position, heading, body)
where exists (select 1 from knowledge_documents where id = '0192f000-0000-7000-8000-00000000d0c4');

insert into knowledge_documents (id, tenant_id, title, body, updated_by)
select '0192f000-0000-7000-8000-00000000d0c5', id, 'AI in Quokra',
       '## What it does

AI in Quokra has three parts, all off by default for each company. Agents can ask for a suggested reply, which they edit or send. The bot can answer customers'' questions its menu doesn''t cover. And when a chat reaches the queue, the agent gets a short summary of it as a private note.

## Documents

Answers come only from the company''s own documents, added in Settings under Knowledge as text or Markdown. Headings split a document into parts, and each answer shows which parts it came from.

## How it stays accurate

The AI is only asked when a document part is close enough to the question, so off-topic messages get the bot''s usual reply and cost nothing. The server drops any answer that doesn''t cite the parts it was given, links somewhere they don''t, or isn''t plain text. Without a good answer, the chat goes to a person.

## Providers

Quokra works with any OpenAI-compatible API: Google''s Gemini by default, OpenAI, or a model you run yourself, for example with Ollama. Without a key, AI is simply off and nothing is sent anywhere.

## Limits

Each chat gets at most 10 AI answers, each IP address 20 an hour, each company 200 AI calls a day and the whole server 1,000, all adjustable. Reaching a limit isn''t an error: the bot carries on as usual and agents see that the limit was reached.',
       'seed'
from tenants where slug = 'demo';

insert into knowledge_chunks (document_id, position, heading, body)
select '0192f000-0000-7000-8000-00000000d0c5', v.position, v.heading, v.body
from (values
    (0, 'What it does', 'AI in Quokra has three parts, all off by default for each company. Agents can ask for a suggested reply, which they edit or send. The bot can answer customers'' questions its menu doesn''t cover. And when a chat reaches the queue, the agent gets a short summary of it as a private note.'),
    (1, 'Documents', 'Answers come only from the company''s own documents, added in Settings under Knowledge as text or Markdown. Headings split a document into parts, and each answer shows which parts it came from.'),
    (2, 'How it stays accurate', 'The AI is only asked when a document part is close enough to the question, so off-topic messages get the bot''s usual reply and cost nothing. The server drops any answer that doesn''t cite the parts it was given, links somewhere they don''t, or isn''t plain text. Without a good answer, the chat goes to a person.'),
    (3, 'Providers', 'Quokra works with any OpenAI-compatible API: Google''s Gemini by default, OpenAI, or a model you run yourself, for example with Ollama. Without a key, AI is simply off and nothing is sent anywhere.'),
    (4, 'Limits', 'Each chat gets at most 10 AI answers, each IP address 20 an hour, each company 200 AI calls a day and the whole server 1,000, all adjustable. Reaching a limit isn''t an error: the bot carries on as usual and agents see that the limit was reached.')
) as v (position, heading, body)
where exists (select 1 from knowledge_documents where id = '0192f000-0000-7000-8000-00000000d0c5');

insert into knowledge_documents (id, tenant_id, title, body, updated_by)
select '0192f000-0000-7000-8000-00000000d0c6', id, 'Privacy and security',
       '## What is stored

Quokra stores the chats, the email and name a guest gives before talking to a person, and agents'' names. Resolved chats are deleted after each company''s retention period, 12 months unless an admin changes it.

## Where a chat came from

Each chat can record the page it started on (the path only), the referring site''s name, campaign tags, country, device, language and time zone. No IP address is stored. A site can pass its own visitor id, but only with the visitor''s consent for analytics.

## Emailed chats

Customers can have their chat emailed to the address they gave in it. Because anyone can type an address, at most 3 such emails a day go to one address, and each says why it was sent.

## Sign-in

Agents sign in through the company''s own identity provider with OIDC; Quokra checks the token''s issuer, audience and roles. Logged-in customers are identified with short-lived tokens signed by the company''s backend. Images are checked for type and size before they''re stored.

## Abuse

Messages, sessions, uploads and AI calls are rate limited, and agents can block a contact. Agents and company admins only see the companies they belong to.',
       'seed'
from tenants where slug = 'demo';

insert into knowledge_chunks (document_id, position, heading, body)
select '0192f000-0000-7000-8000-00000000d0c6', v.position, v.heading, v.body
from (values
    (0, 'What is stored', 'Quokra stores the chats, the email and name a guest gives before talking to a person, and agents'' names. Resolved chats are deleted after each company''s retention period, 12 months unless an admin changes it.'),
    (1, 'Where a chat came from', 'Each chat can record the page it started on (the path only), the referring site''s name, campaign tags, country, device, language and time zone. No IP address is stored. A site can pass its own visitor id, but only with the visitor''s consent for analytics.'),
    (2, 'Emailed chats', 'Customers can have their chat emailed to the address they gave in it. Because anyone can type an address, at most 3 such emails a day go to one address, and each says why it was sent.'),
    (3, 'Sign-in', 'Agents sign in through the company''s own identity provider with OIDC; Quokra checks the token''s issuer, audience and roles. Logged-in customers are identified with short-lived tokens signed by the company''s backend. Images are checked for type and size before they''re stored.'),
    (4, 'Abuse', 'Messages, sessions, uploads and AI calls are rate limited, and agents can block a contact. Agents and company admins only see the companies they belong to.')
) as v (position, heading, body)
where exists (select 1 from knowledge_documents where id = '0192f000-0000-7000-8000-00000000d0c6');

insert into knowledge_documents (id, tenant_id, title, body, updated_by)
select '0192f000-0000-7000-8000-00000000d0c7', id, 'Licence and hosting',
       '## Licence

Quokra is free and open source under the AGPL-3.0. You can run it for your own products and customers without paying anything; if you change Quokra and offer it to others over a network, you share those changes under the same licence.

## Hosting

Quokra is self-hosted: one Docker image from GitHub''s container registry and a PostgreSQL 17 database with the pgvector extension. There''s no hosted paid version. A guide shows how to run a free public demo on Render, Neon and Vercel.

## Updates

Each release has a changelog that says what changed and anything to do when upgrading. Database changes run by themselves when the new version starts.',
       'seed'
from tenants where slug = 'demo';

insert into knowledge_chunks (document_id, position, heading, body)
select '0192f000-0000-7000-8000-00000000d0c7', v.position, v.heading, v.body
from (values
    (0, 'Licence', 'Quokra is free and open source under the AGPL-3.0. You can run it for your own products and customers without paying anything; if you change Quokra and offer it to others over a network, you share those changes under the same licence.'),
    (1, 'Hosting', 'Quokra is self-hosted: one Docker image from GitHub''s container registry and a PostgreSQL 17 database with the pgvector extension. There''s no hosted paid version. A guide shows how to run a free public demo on Render, Neon and Vercel.'),
    (2, 'Updates', 'Each release has a changelog that says what changed and anything to do when upgrading. Database changes run by themselves when the new version starts.')
) as v (position, heading, body)
where exists (select 1 from knowledge_documents where id = '0192f000-0000-7000-8000-00000000d0c7');
