# Plan

Quokra is an embeddable customer support platform. A FAQ bot answers first; when it can't help, or the customer asks, a human agent continues **the same conversation**. Both the customer chat and the agent console are embeddable, so any website or dashboard can add them with a script tag.

| | |
|---|---|
| **Status** | Plan v7: v1 and v2 done; next is putting it in front of the first real company. See the table below and the [changelog](CHANGELOG.md) |
| **Last updated** | 2026-10-30 |
| **Current target** | v1: FAQ bot + human handoff on the web |
| **Stack** | Spring Boot 4.1 (Java 21) + PostgreSQL · React + TypeScript (Vite), served by the server · one repository, one deployable |

**Other docs:** [Architecture](ARCHITECTURE.md) · [API](API.md) · [Embedding guide](EMBEDDING.md) · [Development](DEVELOPMENT.md)

---

## 1. What we're building

```text
Customer on any website / app                 Agent in any dashboard
┌──────────────────────────────┐              ┌──────────────────────────────┐
│ [chat bubble] → chat panel   │              │ agent console (embedded)     │
│ iframe, loaded by sdk.js     │              │ iframe, loaded by sdk.js     │
└──────────────┬───────────────┘              └──────────────┬───────────────┘
               └─────────────────────┬────────────────────────┘
                                     ▼
              ┌─────────────────────────────────────────────┐
              │ quokra server (Spring Boot + PostgreSQL)    │
              │ tenants · conversations · FAQ bot · handoff │
              │ realtime · auth                             │
              └─────────────────────────────────────────────┘
```

1. A customer opens the chat bubble on a website.
2. The **bot** greets them with a menu of common questions and answers from a config file.
3. If the customer asks for a person, the bot fails to understand them twice, it sees an urgent keyword, or it hits an error, the bot asks a guest for their email (and name, optional) in the chat, then the conversation goes to the **queue**.
4. An **agent** (console embedded in their dashboard) accepts it, sees the full history, and replies live.
5. The agent resolves the conversation, or returns it to the bot.

The embedding follows the usual pattern for embeddable widgets (Intercom, Stripe, Calendly): a hosted app inside an iframe, loaded by one `<script>` tag, branded per tenant (slug + publishable key).

---

## 2. Goals

1. Instant answers to common questions.
2. Seamless handoff to a human, in the same conversation.
3. **Customer chat** embeddable on any website with one script tag; no framework or build step for the host.
4. **Agent console** embeddable in any dashboard the same way, using the dashboard's existing login.
5. Per-tenant branding (logo, colour, texts) from config, not code.
6. Small, readable codebase that grows one version at a time.

## 3. Non-goals (until a version below adds them)

AI answers · WhatsApp / Instagram / email · reports · automations · file attachments · native mobile SDKs · voice · tenant self-service.

---

## 4. Versions

| Version | Theme | Contents | Rough size |
|---|---|---|---|
| **v1** | **FAQ bot + handoff** | Chat panel + console (full-page or bubble), both embeddable via `sdk.js`; FAQ bot; handoff; realtime; tenants by publishable key; guests + identified customers; agents via OIDC | 3–4 weeks |
| **v2** | **Daily operations (done)** | Notifications in the console and browser, email alerts (when SMTP is set up), auto-resolve after silence, saved replies, business hours + offline message, the bot and its wording edited in Settings, Settings behind a gear (scoped to the console's company), images (disk or S3), "start a new chat", replying to a message, typing indicator, agent online / away, retention per company within server limits. **Later, when someone asks:** more languages, mobile push, standalone console login | 2–3 weeks |
| **v2.1** | **Company admins** | Admins per company (Admin switch in Settings → Agents, or a claim in the identity provider's token) next to the server-wide admin role, so one server can host many companies, or many products of one company, each managing itself | days |
| v3 | AI answers | Knowledge documents, search with sources, grounded LLM answers, evaluation set, AI summary on handoff | 3–4 weeks |
| v4 | Channels + lookups | WhatsApp Cloud API, email; identity-verified lookups through backend-validated tools | 3–4 weeks |
| v5 | Platform | Tenant self-service, per-tenant identity providers, analytics, CSAT, public release | later |

A version starts only when the previous one is in real use and there's a clear need. Sizes are rough, for one developer.

### v2 notifications, in order

1. **Done. In the console:** a chime and the button badge for new customer messages in *my* chats and for chats transferred to me (today only new queue items chime); the badge counts waiting + my unread; a short toast for what just arrived.
2. **Done. Browser notifications:** system notifications while the dashboard is in another tab or app ("Pat: Hello?"), clicking opens that chat. Shown by `sdk.js` on the host page (an embedded iframe can't ask for the permission), opt-in with `notifications: true` and a one-time "turn on notifications" prompt.
3. **Done. Email** (only when SMTP is configured and answering): to the team when a chat has waited longer than X minutes with no agent online; to the customer when an agent's reply has gone unanswered for a few minutes (per company, off by default).
4. **Mobile push:** later, with a PWA or an app.

### v3 AI answers, plan

The bot answers questions from each company's own documents, shows where the answer came from, and hands
over to a person when it can't answer. The FAQ menu, keywords and handoff stay as they are; AI answers only
the free-text messages nothing else matched.

**Decided**
- Chunks and their vectors live in PostgreSQL with pgvector (Neon has it), per company, deleted with it.
- Companies add knowledge in Settings as pasted text or uploaded `.txt` / `.md` files. PDFs and websites later.
- Answers only from the company's documents. No good match, or the model isn't sure: the usual fallback,
  then handoff. Errors or the AI being off never block the chat.
- Every AI answer shows its sources and is marked as AI in the chat and the console.

- One OpenAI-compatible client in the Spring Boot server (no Python service, no new dependency), set by
  `QUOKRA_AI_BASE_URL`, `QUOKRA_AI_KEY` and model names. Gemini's free tier for local development and the
  public demo, so both behave the same; Ollama, OpenAI, Groq and others work by changing those values.
  Free tiers may use the data to improve their models, so real companies set their own provider and key.
  Tests use a fake AI server, so CI needs no key.
- First the AI only helps agents (3a); answering customers directly is a per-company option on top (3b).

**Guardrails** (for 3b, and most of them for 3a too)
- Only on topic: the documents are searched first; without a close enough match the AI isn't called at all,
  so off-topic requests ("write me a poem") cost nothing and get the usual fallback and handoff.
- Only from the documents: a fixed instruction, the found passages and the question kept apart, and "say you
  don't know" when the passages don't cover it.
- The server checks the answer cites passages it was given; otherwise it's dropped and the chat hands over.
  Plain text, a length limit, no links that aren't in the documents.
- Limits per chat, per IP, per company per day and per server per day; reaching one means FAQ and handoff,
  never an error. AI on / off per company and a server-wide switch.
- Marked as AI with its sources, "Talk to a person" always there, agents see every AI message. No customer
  text in logs, only counts.

**Limits** (env, the defaults below; built in step 5)

| Limit | Default | Env |
|---|---|---|
| AI answers per chat | 10 | `QUOKRA_AI_PER_CHAT` |
| Per IP | 20 per hour | `QUOKRA_AI_PER_IP_HOUR` |
| Per company per day | 200 | `QUOKRA_AI_PER_COMPANY_DAY` |
| Whole server per day | 1,000 | `QUOKRA_AI_PER_SERVER_DAY` |
| AI at all | on only when a key is set | `QUOKRA_AI_ENABLED` |

Reaching a limit, or the provider answering 429, means no AI answer that time: fallback and handoff as
usual, and "AI limit reached" in the console. Counts per company and day are kept in the database. A key
from Google AI Studio without billing can't cost anything; it just stops at Google's free quota, which
Google can change at any time. With billing on, set a spending cap and quotas there too.

**Steps**, each done when its tests pass:
1. **Knowledge:** documents table, Settings → Knowledge (add, edit, remove), split into chunks. **Done**; the
   Settings section stays hidden (`KNOWLEDGE_READY` in `AdminArea.tsx`) until step 3, so nobody fills it in
   before it does anything. Stored in the database, edited in Settings; importing from files can come later.
2. **Search:** embeddings for chunks and questions, pgvector search per company; evaluation set of
   questions with the document that should answer them, and a recall check in the tests. **Done**: with
   `gemini-embedding-001` the right part came first for 15 of 15 questions (scores 0.63+), off-topic
   questions scored 0.47 at most, so the minimum score is 0.55.
3. **Answers:**
   - **3a, for agents:** in the console, a suggested reply from the documents with its sources; the agent
     edits or sends it. Nothing reaches the customer without a person. **Done**, with a per-company switch
     (off by default) and Settings → Knowledge shown when the server has AI; `SuggestionEvalTests` passed
     against Gemini (answers cited, off-topic and injection attempts got nothing).
   - **3b, for customers** (per company, off by default): free-text questions the FAQ didn't match get an
     answer from the documents, with the guardrails above; otherwise fallback and handoff. **Done**: marked
     "AI answer" with its sources, "That helps" / "Talk to a person" buttons, at most 10 per chat;
     `SuggestionEvalTests` runs the same cases for both modes and passed against Gemini.
4. **In the chat and console:** AI answers marked, sources shown, "Talk to a person" always there; agents see
   which chunks were used. Settings → Knowledge shown.
5. **Limits and ops:** on / off per company, the cost limits from decision 10, health check, logs without
   customer text. **Done**: all limits from decision 10 as env settings, daily counts in `ai_usage`, "AI limit
   reached" for suggestions and today's use in Settings → AI. Health stays `/actuator/health` (AI being down
   is a fallback, not an outage); logs name the limit or the HTTP status, never the text.
6. **Handoff summary:** a short AI summary for the agent when a chat reaches the queue.

---

## 5. v1 scope

### In

| Area | Contents |
|---|---|
| Customer chat | Bubble, inline and popup modes; menu buttons; free text; agent name shown; unread badge; reconnect without losing messages |
| Bot | Per-tenant YAML: greeting, menu, keywords, urgent rule, fallback, handoff after N misses, any error → handoff |
| Handoff | States `BOT → WAITING → HUMAN → RESOLVED`, return to bot, reopen on new message; guests leave an email (and optional name) in the chat before joining the queue, linked to a per-tenant contact |
| Agent console | Full-page or bubble mode; paged queue, my chats, resolved list; conversation view with details and activity; accept / reply (replying accepts) / resolve / return to bot / reopen / transfer / block contact; private notes; new-chat sound |
| Tenants | Slug + publishable key + secret key, allowed origins (widget and console), branding, bot file, agent membership |
| Identity | Guests; customers identified by a host-signed token; agents by OIDC access token with role `SUPPORT_AGENT` |
| Realtime | WebSocket (STOMP), authenticated and authorised, catch-up after reconnect |
| Ops | One Docker image (server + built web UI), health endpoint, structured logs, PostgreSQL migrations |

### Out (later versions)

Standalone console login, canned replies, business hours, notifications, auto-resolve, attachments, AI, other channels, reports.

### Built beyond the original v1 list

Admin tab (companies, keys and embed snippets, allowed sites, agents by email invite), real agent login tested against a local Keycloak, unread counts per conversation, search, the customer's previous conversations, "open full view" from the console drawer, light/dark following the host page, per-company wording, logout for shared computers.

---

## 6. v1 build steps

| # | Step | Done when |
|---|---|---|
| 1 | **Skeleton:** Spring Boot app, PostgreSQL in compose, Flyway, health endpoint; Vite React app in `web/` with placeholder routes, served by the server; static test-host page | Server serves the placeholder UI; `test-host` loads it ✅ |
| 2 | **Tenants + sessions:** `tenants` table, demo tenant via migration, public tenant endpoint, guest session tokens | Chat panel shows demo branding and gets a session ✅ |
| 3 | **Conversations:** tables, state machine, widget endpoints | Tests cover every allowed and rejected transition; customer can chat via HTTP ✅ |
| 4 | **FAQ bot:** YAML loading + validation, menu, keywords, urgent rule, fallback, error → handoff | Table-driven tests for every branch ✅ |
| 5 | **Realtime:** STOMP, CONNECT auth, SUBSCRIBE checks, event publishing, catch-up | Two browsers see live messages; reconnect loses nothing ✅ |
| 6 | **Chat panel + sdk.js (customer):** bubble, inline, popup, postMessage protocol, branding, unread badge, CSP `frame-ancestors` | Works embedded on the test-host page from another origin ✅ |
| 7 | **Agent API:** OIDC validation, role + tenant membership, lists, accept (optimistic lock), reply, resolve, return to bot | Concurrent-accept test: exactly one winner ✅ |
| 8 | **Console + sdk.js (agent):** `Quokra.console.mount` (full page) and bubble mode, token hand-over via postMessage, queue, chat view, actions | Full flow by hand: FAQ answer → handoff → accept → reply → resolve → return to bot ✅ |
| 8b | **Contacts + agent tools:** email and name asked in the chat before handoff, contacts, block / unblock, reopen, transfer, private notes, paged lists, counts, console redesign, new-chat sound | Done by hand in the browser; tests for each endpoint ✅ |
| 9 | **Identify:** host-signed identity token, guest → identified merge ✅ | Tests: valid, expired, wrong tenant, tampered |
| 10 | **Hardening + docs:** rate limits, CORS, error format, embed guide checked against reality ✅ | [Security checklist](ARCHITECTURE.md#13-security) passes |
| 11 | **Ship it:** one Docker image (server + web UI), `docker compose up` for the whole stack (Quokra, PostgreSQL, Keycloak, demo data), structured JSON logs, a deploy guide ✅ | Someone new runs it from the README in minutes |

---

## 7. Open decisions

| # | Decision | Default |
|---|---|---|
| 1 | Agent role name in the identity provider | `SUPPORT_AGENT` |
| 2 | Token audience for the server | `quokra-api` |
| 3 | Guests allowed, or identified customers only? | Allow guests |
| 4 | Domain | One: `chat.<domain>` (UI, API and WebSocket) |
| 5 | Message retention | Each company chooses (default 12 months) within the server's limits (`QUOKRA_RETENTION_MIN_MONTHS` / `_MAX_MONTHS`, default 1–60); resolved chats are deleted nightly. Done in v2 |
| 6 | Languages | English in v1; more via per-language bot files in v2 |
| 7 | Repository licence | AGPL-3.0, public on GitHub (decided 2026-10-30) |
| 8 | v3: AI provider and models (answers and embeddings) | OpenAI-compatible client; Gemini free tier locally and on the demo (decided 2026-11-02) |
| 9 | v3: AI inside the Spring Boot server, or a separate Python service | Spring Boot (decided 2026-11-02) |
| 10 | v3: cost limits | 10 per chat, 20 per IP per hour, 200 per company per day, 1,000 per server per day, all set by env (decided 2026-11-02) |

---

## 8. Ideas borrowed

| From | Idea |
|---|---|
| Chatwoot | Conversation status as the bot/human switch; notes on handoff (v2); signed webhooks (v4) |
| Libredesk | Simple deployment; AI assistant that hands off to agents (v3) |
| Intercom / Stripe / Calendly embeds | Hosted iframe app + `sdk.js`, publishable key per tenant, postMessage protocol with origin checks |

**Licence rule:** read anything; copy code only from MIT/Apache projects (keeping their notice) or our own projects; never copy AGPL or proprietary code.
