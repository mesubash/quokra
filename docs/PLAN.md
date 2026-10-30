# Plan

Quokra is an embeddable customer support platform. A FAQ bot answers first; when it can't help, or the customer asks, a human agent continues **the same conversation**. Both the customer chat and the agent console are embeddable, so any website or dashboard can add them with a script tag.

| | |
|---|---|
| **Status** | Plan v7: v1 done (steps 1–11); v2 next |
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
| v2 | Daily operations | **Notifications** (see below), auto-resolve after silence, standalone console login (OIDC in the browser), canned replies, business hours + offline message, attachments, FAQ editing in the Admin tab, more languages, retention job | 2–3 weeks |
| v3 | AI answers | Knowledge documents, search with sources, grounded LLM answers, evaluation set, AI summary on handoff | 3–4 weeks |
| v4 | Channels + lookups | WhatsApp Cloud API, email; identity-verified lookups through backend-validated tools | 3–4 weeks |
| v5 | Platform | Tenant self-service, per-tenant identity providers, analytics, CSAT, public release | later |

A version starts only when the previous one is in real use and there's a clear need. Sizes are rough, for one developer.

### v2 notifications, in order

1. **In the console:** a chime and the button badge for new customer messages in *my* chats and for chats transferred to me (today only new queue items chime); the badge counts waiting + my unread; a short toast for what just arrived.
2. **Browser notifications:** system notifications while the dashboard is in another tab or app ("Pat: Hello?"), clicking opens that chat. Shown by `sdk.js` on the host page (an embedded iframe can't ask for the permission), opt-in with `notifications: true` and a one-time "turn on notifications" prompt.
3. **Email:** to the team when a chat has waited longer than X minutes with no agent online; to the customer when an agent replies after they've left. Needs an email provider.
4. **Mobile push:** later, with a PWA or an app.

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
| 5 | Message retention | 12 months, then delete (job in v2) |
| 6 | Languages | English in v1; more via per-language bot files in v2 |
| 7 | Repository licence | Private for now; decide before any public release |

---

## 8. Ideas borrowed

| From | Idea |
|---|---|
| Chatwoot | Conversation status as the bot/human switch; notes on handoff (v2); signed webhooks (v4) |
| Libredesk | Simple deployment; AI assistant that hands off to agents (v3) |
| Intercom / Stripe / Calendly embeds | Hosted iframe app + `sdk.js`, publishable key per tenant, postMessage protocol with origin checks |

**Licence rule:** read anything; copy code only from MIT/Apache projects (keeping their notice) or our own projects; never copy AGPL or proprietary code.
