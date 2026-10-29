# Architecture

How Quokra is built: its parts, their responsibilities, the main flows, data, security, and the decisions behind them. Scope: **v1**, with notes where later versions change things.

**Other docs:** [Plan](PLAN.md) · [API](API.md) · [Embedding guide](EMBEDDING.md) · [Development](DEVELOPMENT.md)

---

## Contents

1. [Overview](#1-overview)
2. [Components](#2-components)
3. [Server internals](#3-server-internals)
4. [Web UI internals](#4-web-ui-internals)
5. [Conversation state machine](#5-conversation-state-machine)
6. [Bot engine](#6-bot-engine)
7. [Tenancy](#7-tenancy)
8. [Identity and tokens](#8-identity-and-tokens)
9. [Main flows](#9-main-flows)
10. [Realtime](#10-realtime)
11. [Data model](#11-data-model)
12. [Errors and failure handling](#12-errors-and-failure-handling)
13. [Security](#13-security)
14. [Observability](#14-observability)
15. [Deployment](#15-deployment)
16. [Limits and upgrade paths](#16-limits-and-upgrade-paths)
17. [Decision records](#17-decision-records)

---

## 1. Overview

```text
 Host website (any origin)                    Host dashboard (any origin)
 ┌──────────────────────────────┐             ┌──────────────────────────────┐
 │ sdk.js                       │             │ sdk.js                       │
 │  ├ bubble button             │             │  └ Quokra.console.mount(el,  │
 │  └ iframe ─────────┐         │             │       { getToken })          │
 └────────────────────┼─────────┘             │     iframe ──────┐           │
                      │                       └──────────────────┼───────────┘
                      ▼                                          ▼
       ┌─────────────────────────────────────────────────────────────────┐
       │ quokra server (Spring Boot)          https://chat.<domain>      │
       │                                                                 │
       │  static (built from web/):  /sdk.js · /w/<slug> · /console      │
       │  API:                       /api/v1/widget/** · /api/v1/agent/**│
       │  realtime:                  /ws (STOMP)                         │
       │  inside:                    bot engine · state machine · auth   │
       └───────────────┬───────────────────────────────┬─────────────────┘
                       ▼                               ▼
                 PostgreSQL                    OIDC provider (JWKS)
                                               for agent tokens
```

Two deployable things: the **quokra server** (one Docker image that also serves the web UI) and **PostgreSQL**. Hosts only add a script tag. Because the iframes and the API share one origin, the browser needs no CORS for chat traffic.

---

## 2. Components

| Component | Responsibility | Not responsible for |
|---|---|---|
| **quokra server** | Truth: tenants, conversations, messages, state changes, bot replies, auth, realtime events. Also serves the built web UI and `sdk.js`, and sets CSP `frame-ancestors` per tenant | — |
| **web UI** (`web/`, React SPA) | Customer chat panel (`/w/<slug>`) and agent console (`/console`); calls the API | Business rules (it only calls the API) |
| **sdk.js** | Runs in the host page: creates bubble/iframes, passes tokens and open/close commands via postMessage, resizes inline frames | Talking to the server directly |
| **PostgreSQL** | Persistent data | — |
| **OIDC provider** | Issues agent access tokens; server validates them via JWKS | — |
| **Host backend** (optional) | Signs short-lived identity tokens for logged-in customers with the tenant secret | — |

The iframes are served by the same server as the API, so they call it on the same origin (REST + WebSocket). There is no separate frontend server.

---

## 3. Server internals

Package by feature. Each feature owns its controller, service, repository and DTOs.

```text
io.github.mesubash.quokra
├── tenant/          Tenant entity, lookup by pk/slug, branding, allowed origins, agent membership
├── session/         Guest sessions, identify, session token signing/verification
├── conversation/    Conversation + Message + Event entities, ConversationService, StateMachine
├── bot/             BotConfig (YAML), BotConfigRegistry, BotEngine
├── widget/          WidgetController: customer endpoints
├── agent/           AgentController: agent endpoints
├── realtime/        STOMP config, CONNECT/SUBSCRIBE interceptors, EventPublisher
├── security/        Filter chains (session token vs OIDC), current principal, rate limiter
├── web/             Serves the SPA (index.html for /w/** and /console), sets frame-ancestors per tenant
└── common/          Error envelope, exception handler, clock, ids
```

### 3.1 Request path (customer sends a message)

```text
WidgetController.postMessage
  → SessionAuth (verify chat session token → tenant + visitor/customer)
  → RateLimiter
  → ConversationService.addCustomerMessage   (one DB transaction)
       ├─ idempotency: clientMessageId already stored? → return existing
       ├─ insert message
       ├─ if status == BOT → BotEngine.reply(conversation, message)
       │      └─ returns BotDecision: Answer | Handoff | Fallback
       ├─ apply decision (bot messages, state change via StateMachine)
       └─ collect domain events
  → after commit: EventPublisher sends events to STOMP topics
```

Events are published **after commit**, so clients never see data that was rolled back.

### 3.2 Key classes

| Class | Role |
|---|---|
| `StateMachine` | Static table of allowed transitions; `transition(conversation, target, actor, reason)` validates, updates, appends an event, or throws `InvalidTransition` |
| `ConversationService` | All writes to conversations and messages; the only caller of `StateMachine` |
| `BotEngine` | Pure function `(BotConfig, Conversation, Message) → BotDecision`; no DB or network access, easy to unit test |
| `BotConfigRegistry` | Loads and validates `bots/*.yaml` at startup; fails fast on invalid config |
| `SessionTokens` | Signs and verifies chat session tokens |
| `IdentityTokens` | Verifies host-signed identity tokens with the tenant secret |
| `EventPublisher` | Maps domain events to STOMP messages |

---

## 4. Web UI internals

React + TypeScript single-page app built with Vite. The production build is copied into the server's static resources, so it ships inside the server image.

```text
web/
├── src/
│   ├── chat/                   customer panel: message list, composer, menu buttons
│   ├── console/                agent console: queue, my chats, conversation view
│   ├── lib/
│   │   ├── api.ts              typed REST client (widget + agent)
│   │   ├── realtime.ts         STOMP client, reconnect + catch-up
│   │   ├── brand.ts            primary colour → palette with contrast checks
│   │   └── bridge.ts           postMessage helpers with origin checks
│   └── main.tsx                routes: /w/:slug → chat, /console → console
├── public/sdk.js               embed loader (plain JS, no dependencies)
└── vite.config.ts              dev server proxies /api and /ws to the server
```

- **Serving:** the server returns `index.html` for `/w/**` and `/console`, with `Content-Security-Policy: frame-ancestors <tenant origins>` set per tenant (looked up from `pk` or `tenant`). Static assets are served with long cache headers; `sdk.js` with a short one so updates reach hosts quickly.
- **Branding:** the panel fetches `GET /widget/tenants/{pk}` on load and applies the palette as CSS variables.
- **Storage:** the chat session token is kept in `localStorage` on the Quokra origin (per host site, since browsers partition third-party storage). Clearing it starts a new guest session.
- **Rendering:** message bodies are rendered as text nodes, never as HTML.

---

## 5. Conversation state machine

```text
              HANDOFF (customer request / fallback limit / urgent keyword / bot error)
     BOT ─────────────────────────────────────────────────────────────► WAITING
      ▲                                                                    │
      │ RETURN_TO_BOT (agent)                                ACCEPT (agent)│
      │                                                                    ▼
      └───────────────────────────────────────────────────────────────  HUMAN
                                                                           │
                                                          RESOLVE (agent)  ▼
                                                                       RESOLVED
     RESOLVED ──REOPEN (new customer message)──► BOT
```

| From | Event | To | Actor | Side effects |
|---|---|---|---|---|
| — | CREATE | `BOT` | customer | greeting + menu |
| `BOT` | HANDOFF | `WAITING` | bot / customer | handoff message; `handoff_reason`; priority; queue event |
| `WAITING` | ACCEPT | `HUMAN` | agent | `assignee_sub` set; system message "<name> joined" |
| `WAITING` | RESOLVE | `RESOLVED` | agent | for spam or duplicates |
| `HUMAN` | RESOLVE | `RESOLVED` | agent | resolved message |
| `HUMAN` | RETURN_TO_BOT | `BOT` | agent | `assignee_sub` cleared; menu sent |
| `RESOLVED` | REOPEN | `BOT` | customer | `fallback_count` reset; bot handles the new message |

Any other combination throws `InvalidTransition` (HTTP 409). `ACCEPT` uses the `version` column for optimistic locking; a lost race returns `409 ALREADY_ACCEPTED`.

---

## 6. Bot engine

```text
decide(config, conversation, message):
  if matches(config.urgent.keywords):       return Handoff(URGENT, priority URGENT, config.urgent.message)
  if message.optionId == HUMAN_OPTION or matches(config.human_keywords): return Handoff(CUSTOMER_REQUEST)
  if message.optionId in config.menu:       return Answer(option.answer, menu)
  if keywordMatch(config.menu, message):    return Answer(best.answer, menu)
  if conversation.isFirstCustomerMessage:   return Answer(config.greeting, menu)
  if conversation.fallback_count + 1 >= config.max_fallbacks: return Handoff(FALLBACK_LIMIT)
  return Fallback(config.fallback, menu)
```

- Urgent keywords are checked **first**, so they always win: the conversation gets priority `URGENT` and the tenant's urgent message, even if the text also matches a menu option or asks for a person. Each tenant chooses its own urgent keywords (e.g. "outage", "payment failed", or real emergencies).
- Keyword matching: lowercase, accents stripped, whole-word match; the option with the most keyword hits wins; ties go to the first option in the file.
- Any exception inside the engine is caught by `ConversationService` → `Handoff(BOT_ERROR)`.
- v3 replaces "no match" with retrieval + LLM; the decision type stays the same.

---

## 7. Tenancy

| Concept | v1 implementation |
|---|---|
| Tenant | Row in `tenants` (slug, publishable key, encrypted secret key, branding, allowed origins, bot file) |
| Created by | Flyway migration (demo) or a CLI command (`create-tenant`) |
| Widget access | `pk` in the embed code → tenant; origin checked against `widget_origins` |
| Console access | Agent must have role `SUPPORT_AGENT` **and** a row in `tenant_agents` for that tenant |
| Isolation | Every query filters by `tenant_id`; tested |

One OIDC issuer for agents in v1. Per-tenant identity providers come in v5.

---

## 8. Identity and tokens

| Token | Issued by | Holder | Lifetime | Used for |
|---|---|---|---|---|
| **Chat session token** | quokra server (`SESSION_SIGNING_KEY`, HS256) | Chat panel (`localStorage`) | 30 days | All widget REST calls + WebSocket for that customer |
| **Identity token** | Host backend (tenant secret key, HS256) | Host page → passed once via `identify` | ≤ 5 minutes | Proving who a logged-in customer is |
| **Agent access token** | OIDC provider | Host dashboard → passed to the console iframe | Provider's setting | Agent REST calls + WebSocket |

### 8.1 Chat session token claims

`sub` (visitor id), `tid` (tenant id), `cus` (customer sub, after identify), `iat`, `exp`.

### 8.2 Identity token claims (signed by the host backend)

`sub` (host's user id, required), `name`, `email`, `iat`, `exp` (≤ 5 min after `iat`). The server rejects tokens with a lifetime over 5 minutes, unknown tenants, or a bad signature.

The tenant secret must be readable by the server to verify HMAC signatures, so it's stored **encrypted** with `TENANT_SECRET_ENCRYPTION_KEY`, not hashed. (v5 option: hosts sign with their own private key and register a JWKS URL instead.)

### 8.3 Agent token requirements

Valid signature via the issuer's JWKS, audience `quokra-api`, role `SUPPORT_AGENT`, not expired. The agent's display name comes from the `name` claim.

---

## 9. Main flows

### 9.1 Guest opens the chat and gets a FAQ answer

```text
host page        sdk.js           chat panel (iframe)        quokra server
   │ load script ──►│                    │                          │
   │                │ bubble click ─────►│ open                     │
   │                │                    │ GET /widget/tenants/{pk} ─►│
   │                │                    │ POST /widget/sessions ────►│ → session token
   │                │                    │ POST /widget/conversation ►│ → greeting + menu
   │                │                    │ CONNECT /ws, SUBSCRIBE ───►│
   │                │                    │ (customer taps "Plans")  │
   │                │                    │ POST …/messages {optionId}►│ BotEngine → Answer
   │                │                    │◄── message.created (bot) ─│
```

### 9.2 Handoff and agent reply

```text
chat panel                 quokra server                      console (agent)
   │ "talk to a person" ──────►│ BOT → WAITING                      │
   │◄─ handoff message ────────│── conversation.waiting ───────────►│ queue updates, sound
   │                           │◄── POST /agent/…/accept ───────────│
   │◄─ "<agent> joined" ───────│ WAITING → HUMAN                    │
   │                           │◄── POST /agent/…/messages ─────────│
   │◄─ message.created ────────│                                    │
   │ customer replies ────────►│ (status HUMAN: bot silent) ───────►│ message.created
   │                           │◄── POST /agent/…/resolve ──────────│
   │◄─ resolved message ───────│ HUMAN → RESOLVED                   │
```

### 9.3 Console embedded in a dashboard

```text
dashboard page            sdk.js                    console iframe           quokra server
   │ Quokra.console.mount ──►│ create iframe /console ──►│                        │
   │                         │◄──────── console.ready ───│                        │
   │ getToken() ◄────────────│                           │                        │
   │                         │── auth.token {token} ────►│ (origin checked)       │
   │                         │                           │ GET /agent/conversations ►│ (JWT validated)
   │                         │◄──── auth.request ────────│ (on 401 / near expiry) │
   │ getToken() ◄────────────│── auth.token ────────────►│                        │
```

The dashboard's origin must be in the tenant's `console_origins`; the console ignores messages from any other origin.

### 9.4 Identify a logged-in customer

```text
host backend          host page / sdk.js         chat panel               quokra server
   │ sign identity token ──►│                         │                          │
   │                        │ Quokra.chat.identify ──►│ identify {token}         │
   │                        │                         │ POST /widget/sessions/identify ►│
   │                        │                         │                          │ verify → link customer_sub,
   │                        │                         │◄─ new session token ─────│ attach open guest conversation
```

Merge rules on identify:

| Customer already has an open conversation? | Visitor has an open guest conversation? | Result |
|---|---|---|
| No | No | Nothing to merge |
| No | Yes | The guest conversation gets `customer_sub` |
| Yes (e.g. on another device) | No | The session joins the customer's conversation |
| Yes | Yes | Guest messages are moved into the customer's conversation (with a system note); the guest conversation is resolved |

### 9.5 Reconnect

The realtime client remembers the last message id per conversation. On reconnect it re-subscribes and calls `GET …/messages?after={lastId}`; duplicates are dropped by message id.

---

## 10. Realtime

| Item | v1 |
|---|---|
| Protocol | STOMP over WebSocket at `/ws` (Spring `spring-boot-starter-websocket`) |
| Broker | In-memory simple broker (single instance) |
| Auth | Token in the STOMP CONNECT header (`Authorization: Bearer …`); session token or agent token |
| Authorisation | `SUBSCRIBE /topic/conversations/{id}`: customer of that conversation, or an agent of its tenant. `SUBSCRIBE /topic/tenants/{tid}/queue`: agents of that tenant |
| Delivery | At most once; gaps filled by the catch-up endpoint |
| Heartbeats | 10 s both ways |

Event payloads are listed in [API.md](API.md#5-realtime-events).

---

## 11. Data model

```sql
create table tenants (
  id                 uuid primary key,
  slug               text not null unique,
  display_name       text not null,
  publishable_key    text not null unique,
  secret_key_enc     bytea,                   -- added in step 9 (identify); encrypted, see §8.2
  widget_origins     text[] not null default '{}',
  console_origins    text[] not null default '{}',
  logo_url           text,
  primary_color      text,
  bot_config         text not null,           -- file name in bots/
  created_at         timestamptz not null
);

create table tenant_agents (
  tenant_id          uuid not null references tenants,
  agent_sub          text not null,
  primary key (tenant_id, agent_sub)
);

create table conversations (
  id                 uuid primary key,
  tenant_id          uuid not null references tenants,
  status             text not null,           -- BOT | WAITING | HUMAN | RESOLVED
  visitor_id         uuid not null,
  customer_sub       text,
  customer_name      text,
  customer_email     text,
  channel            text not null default 'WEB',
  assignee_sub       text,
  assignee_name      text,
  priority           text not null default 'NORMAL',  -- NORMAL | HIGH | URGENT
  handoff_reason     text,
  fallback_count     int  not null default 0,
  version            int  not null default 0,
  created_at         timestamptz not null,
  updated_at         timestamptz not null,
  last_message_at    timestamptz not null,
  resolved_at        timestamptz
);
create index on conversations (tenant_id, status, priority, last_message_at);
create unique index one_open_conversation_per_visitor
  on conversations (tenant_id, visitor_id) where status <> 'RESOLVED';
create unique index one_open_conversation_per_customer
  on conversations (tenant_id, customer_sub) where status <> 'RESOLVED' and customer_sub is not null;

create table messages (
  id                 uuid primary key,
  conversation_id    uuid not null references conversations,
  sender_type        text not null,           -- CUSTOMER | BOT | AGENT | SYSTEM
  sender_sub         text,
  sender_name        text,
  body               text not null,
  options            jsonb,                   -- menu buttons [{ id, title }], added in step 4
  client_message_id  text,
  created_at         timestamptz not null,
  unique (conversation_id, client_message_id)
);
create index on messages (conversation_id, created_at);

create table conversation_events (
  id                 uuid primary key,
  conversation_id    uuid not null references conversations,
  type               text not null,           -- CREATED | HANDOFF | ACCEPTED | RESOLVED | RETURNED_TO_BOT | REOPENED | IDENTIFIED
  actor_type         text not null,           -- CUSTOMER | BOT | AGENT | SYSTEM
  actor_sub          text,
  reason             text,                    -- e.g. URGENT for a handoff
  created_at         timestamptz not null
);
create index on conversation_events (conversation_id, created_at);
```

- Message ids are UUIDv7 (time-ordered), so `after={id}` paging is a simple comparison.
- Message `body` max 2,000 characters.
- The partial unique indexes guarantee **one open conversation per visitor, and per identified customer, per tenant**.

---

## 12. Errors and failure handling

| Failure | Behaviour |
|---|---|
| Bot engine throws | Conversation → `WAITING` with reason `BOT_ERROR`; customer gets the handoff message |
| Invalid bot YAML | Server refuses to start, with the file and field in the error |
| Duplicate send (retry) | Same `clientMessageId` → existing message returned, no duplicate |
| Two agents accept | One wins; the other gets `409 ALREADY_ACCEPTED` and the queue refreshes |
| WebSocket drops | Client reconnects with backoff, then catches up via REST |
| Token expired | Widget: new guest session (an identified user must identify again). Console: `auth.request` to the host |
| Server down | Panel shows "Chat is unavailable right now" with a retry button |

All errors use one envelope: `{ "error": { "code": "...", "message": "...", "details": { } } }`. Codes are listed in [API.md](API.md#11-errors).

---

## 13. Security

| Area | Measure | Checked by |
|---|---|---|
| Who can embed the chat | Chat app sends `frame-ancestors <widget_origins>` for `/w/<slug>` | Integration test |
| Who can embed the console | `frame-ancestors <console_origins>` for `/console`; console accepts postMessage only from those origins | Integration test |
| postMessage | Checks `event.origin`, `event.source`, and `data.source == 'quokra'`; never sends tokens to `*` | Unit tests for `bridge.ts` and `sdk.js` |
| Customer data access | Session token bound to tenant + visitor; every widget query filters by both | Integration tests |
| Agent data access | JWT (JWKS, audience, expiry) + role + `tenant_agents` membership on every endpoint and topic | Integration tests |
| Identity tokens | HS256 with tenant secret, `exp − iat ≤ 5 min`, tenant must match `pk` | Unit tests |
| WebSocket | Authenticated CONNECT; authorised SUBSCRIBE | Integration tests |
| Abuse | Rate limits: 20 messages/min per session, 5 new sessions/min per IP; body length limit | Unit tests |
| CORS | None needed for the UI (same origin); cross-origin API calls are rejected | Config test |
| Content | Bodies stored and rendered as text | Component test |
| Secrets | Environment variables only; tenant secrets encrypted at rest; never logged | Review |
| Urgent keywords | Urgent rule always produces a top-priority handoff | Unit test |

**Checklist for v1 release:** every row above has its test or review done.

---

## 14. Observability

- **Health:** Spring Actuator `/actuator/health` (DB check), exposed to the load balancer only.
- **Logs:** JSON to stdout with `requestId`, `tenantId`, `conversationId`; never message bodies or tokens.
- **Metrics (Actuator/Micrometer):** messages per minute, handoffs by reason, queue size, time to accept, WebSocket connections.

---

## 15. Deployment

```text
   https://chat.<domain>
          │
   nginx (TLS, WebSocket upgrade for /ws)
          │
   quokra server (Docker image: Spring Boot + built web UI)
          │
   PostgreSQL
```

The Docker image is built in two stages: Node builds `web/` → output copied into the Spring Boot jar → JRE runtime image.

| Setting | Where |
|---|---|
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` | server env |
| `OIDC_ISSUER_URI`, `OIDC_AUDIENCE` | server env |
| `SESSION_SIGNING_KEY`, `TENANT_SECRET_ENCRYPTION_KEY` | server env (secrets) |
| `PUBLIC_BASE_URL` (e.g. `https://chat.<domain>`) | server env |

Migrations run on server start (Flyway). Backups: PostgreSQL dumps with the existing backup routine.

---

## 16. Limits and upgrade paths

| v1 choice | Limit | Upgrade when needed |
|---|---|---|
| In-memory STOMP broker | One server instance | Broker relay (RabbitMQ) or Redis pub/sub |
| In-memory rate limiter | Per instance | Redis-backed limiter |
| Bot config in YAML files | Redeploy to change answers | DB-backed config + console editor (v2) |
| Tenants via migration/CLI | No self-service | Admin UI (v5) |
| One OIDC issuer for agents | All tenants' agents in one IdP | Per-tenant issuers (v5) |
| HS256 identity tokens | Shared secret per tenant | Host JWKS + RS256/ES256 (v5) |

---

## 17. Decision records

| # | Decision | Why | Alternatives rejected |
|---|---|---|---|
| D1 | Build our own instead of Chatwoot/Libredesk | Full ownership, embeddable console, own branding, small codebase | Chatwoot (separate dashboard, branding, Rails fork); Libredesk (AGPL, can't own changes) |
| D2 | Customer chat and console as iframes loaded by `sdk.js` | Works on any host without a build step; style isolation; one deploy updates all hosts; the usual pattern for embeddable widgets (Intercom, Stripe, Calendly) | npm React components (only React hosts; every host redeploys to update) |
| D3 | Web UI is a Vite React SPA served by the Spring Boot server | One deployable, one origin (no CORS), no frontend server to run; the panel opens on click, so server rendering adds nothing | Next.js app as a second service |
| D4 | Spring Boot + PostgreSQL | Same as other services: OIDC resource server, Flyway, deployment, monitoring | FastAPI (better AI libraries, but AI comes in v3 and can be a separate service) |
| D5 | STOMP over WebSocket | Built into Spring, topics + auth interceptors out of the box | Raw WebSocket (more custom code), SSE (no client → server messages) |
| D6 | Deterministic YAML bot in v1 | Predictable, testable, no AI risk; answers editable without code | LLM from day one |
| D7 | Optimistic locking for accept | Simple and correct for low contention | Pessimistic locks, queues |
| D8 | Events published after commit | Clients never see rolled-back data | Publishing inside the transaction |
| D9 | Catch-up endpoint instead of guaranteed delivery | Simple; REST is the source of truth | Persistent message broker with acknowledgements |
| D10 | One repository for server, web UI and docs | One developer, one API contract: changes land together | Separate repos per part |
| D11 | Console offers full-page and bubble modes | Dedicated agents get a full page; staff who answer occasionally get a floating badge + drawer on any dashboard page | Full-page only |
