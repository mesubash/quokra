# API

The contract between the chat app, hosts and the support server: REST endpoints, realtime events, the postMessage protocol, and the `sdk.js` JavaScript API. Scope: **v1**.

**Other docs:** [Plan](PLAN.md) · [Architecture](ARCHITECTURE.md) · [Embedding guide](EMBEDDING.md) · [Development](DEVELOPMENT.md)

---

## Contents

1. [Conventions](#1-conventions)
2. [Authentication](#2-authentication)
3. [Widget endpoints](#3-widget-endpoints)
4. [Agent endpoints](#4-agent-endpoints)
5. [Realtime events](#5-realtime-events)
6. [Shared types](#6-shared-types)
7. [postMessage: customer chat](#7-postmessage-customer-chat)
8. [postMessage: agent console](#8-postmessage-agent-console)
9. [sdk.js JavaScript API](#9-sdkjs-javascript-api)
10. [UI URLs](#10-ui-urls)
11. [Errors](#11-errors)

---

## 1. Conventions

- Base URL: `https://chat.<domain>/api/v1`
- JSON in and out, UTF-8, `camelCase` fields.
- Timestamps: ISO 8601 UTC (`2026-10-29T08:15:00Z`).
- IDs: UUIDs; message ids are UUIDv7 (time-ordered).
- Lists: `?limit=` (default 50, max 200) and cursor `?before=` / `?after=` (an id). Responses: `{ "items": [...], "nextCursor": "..." | null }`.
- Writes that can be retried take a `clientMessageId` (any unique string from the client, max 64 chars).
- The OpenAPI spec is served at `/v3/api-docs` (springdoc); this document is the human-readable version.

---

## 2. Authentication

| Caller | Header | Token |
|---|---|---|
| Chat panel (customer) | `Authorization: Bearer <chat session token>` | From `POST /widget/sessions` |
| Console (agent) | `Authorization: Bearer <agent access token>` | OIDC token from the host dashboard; audience `quokra-api`, role `SUPPORT_AGENT` |
| Public | none | Only `GET /widget/tenants/{pk}` and `POST /widget/sessions` |

WebSocket: send the same bearer token in the STOMP `CONNECT` frame header `Authorization`.

---

## 3. Widget endpoints

### 3.1 `GET /widget/tenants/{pk}`: public tenant config

```json
200
{
  "slug": "demo",
  "displayName": "Demo Company",
  "branding": { "logoUrl": "https://…/logo.png", "primaryColor": "#0A7C66" },
  "teaser": "Hi 👋 Need any help? We're here.",
  "texts": {
    "online": "Online · replies instantly",
    "waiting": "Connecting you with our team…",
    "closed": "Conversation closed · write again any time",
    "placeholder": "Reply to {name}…",
    "offline": "Away now · we'll reply when we're back"
  },
  "open": true,
  "attachments": false,
  "teamOnline": true
}
```

`teamOnline` is true while someone of the company's team is online (see 4.21); the chat shows `texts.online` / `texts.waiting` only then, and `texts.offline` otherwise. `attachments` is true when the company allows images and the server's storage works; the chat shows its image button only then. `open` is false outside the tenant's opening hours (always true without any); the chat then shows `texts.offline` in its header. `teaser` and `texts` come from the tenant's bot file (see ARCHITECTURE.md §6); `texts` always has every field, with defaults for any the file leaves out. `404 TENANT_NOT_FOUND` for unknown keys.

### 3.2 `POST /widget/sessions`: start a guest session

```json
// request
{ "publishableKey": "pk_live_…" }

// 201
{ "sessionToken": "eyJ…", "visitorId": "0192…", "expiresAt": "2026-11-28T08:15:00Z" }
```

### 3.3 `POST /widget/sessions/identify`: link a logged-in customer

Auth: chat session token.

```json
// request
{ "identityToken": "eyJ…" }   // signed by the host backend, see EMBEDDING.md §4

// 200
{
  "sessionToken": "eyJ…",
  "visitorId": "0192…",
  "customer": { "sub": "user-123", "name": "Asha", "email": "asha@example.com" },
  "expiresAt": "2026-11-28T08:15:00Z"
}
```

The token must be HS256, signed with the tenant's secret key, valid for at most 5 minutes (30 seconds of clock skew allowed), with `sub` set. The new session token replaces the old one; it carries the customer, so the customer's conversation is found on any device.

What happens to conversations:

| Customer already has an open conversation? | This visitor has an open guest conversation? | Result |
|---|---|---|
| No | No | Nothing yet; the next start creates one for the customer |
| No | Yes | The guest conversation becomes the customer's. If the bot was asking for an email, the handoff goes ahead |
| Yes | No | The session joins the customer's conversation |
| Yes | Yes | Guest messages move into the customer's conversation (with a system note); the guest conversation is resolved |

If this browser's open conversation belongs to a *different* customer (someone else logged in here before), the new customer gets a fresh visitor id and doesn't see it. Errors: `401 IDENTITY_TOKEN_INVALID` (bad signature, wrong tenant, too long-lived, or identify not set up for the tenant), `401 IDENTITY_TOKEN_EXPIRED`.

### 3.4 `GET /widget/conversation`: current conversation

```json
200  { "conversation": Conversation | null }
```

### 3.5 `POST /widget/conversation`: start or resume

Returns the open conversation, creating one (status `BOT`, greeting + menu) if none exists.

```json
201 / 200
{ "conversation": Conversation, "messages": [Message, …] }
```

### 3.6 `GET /widget/conversation/messages?after={messageId}&limit=50`

History, oldest first after the cursor. Use without `after` for the latest page.

```json
200 { "items": [Message, …], "nextCursor": "0192…" }
```

### 3.7 `POST /widget/conversation/messages`: send

```json
// free text
{ "clientMessageId": "c-7f3a", "body": "How do I reset my password?" }

// menu button
{ "clientMessageId": "c-7f3b", "optionId": "account" }

// a reply to an earlier message
{ "clientMessageId": "c-7f3c", "body": "Yes, that one", "replyTo": "0192…" }

// 201
{ "message": Message, "conversation": Conversation }
```

`replyTo` (optional) must be a customer's or agent's message in the same conversation (`400 VALIDATION_ERROR` otherwise); the stored message then carries a `replyTo` quote (see `Message`). The customer message is stored first. Bot replies arrive as realtime events, and also in the next `GET …/messages`. Errors: `409 NO_OPEN_CONVERSATION`, `422 MESSAGE_TOO_LONG`, `429 RATE_LIMITED`.

### 3.8 `POST /widget/conversation/handoff`: "Talk to a person"

No request body.

```json
// 200
{ "conversation": Conversation }
```

Safe to call again: if the conversation is already `WAITING` or `HUMAN`, it is returned unchanged.

A guest who hasn't left their details yet isn't queued straight away. The conversation stays `BOT`, and the bot asks in the chat: first for an email (it asks again if the reply doesn't look like one), then for a name, with a **Skip** option (`optionId: "skip"`). `conversation.asking` is `"EMAIL"` or `"NAME"` meanwhile, so the widget can adjust the input. The answers are normal messages (`3.7`). Once both are in, the conversation is linked to the tenant's contact with that email (matched without case, created the first time) and joins the queue. The same happens when the bot hands off by itself (urgent keyword, too many misses). Logged-in customers (identify) skip this.

The email isn't verified, so it only groups conversations for agents: a customer never sees other conversations through it. A blocked contact gets `403 CONTACT_BLOCKED`.

### 3.10 Images

`POST /widget/conversation/attachments` (multipart: `file`, optional `clientMessageId` and `replyTo`) sends an image, like a message:

```json
// 201
{ "message": Message, "conversation": Conversation }   // message.attachment: { name, contentType, size }
```

PNG, JPEG, GIF or WebP up to 5 MB, recognised by the file's first bytes (not its name or declared type; SVG is refused). Errors: `403 ATTACHMENTS_OFF` (the company hasn't allowed images), `415 UNSUPPORTED_FILE`, `413 FILE_TOO_LARGE`, `503 STORAGE_UNAVAILABLE`, `429 RATE_LIMITED` (shares the message limit). The bot doesn't answer images.

`GET /widget/conversation/messages/{messageId}/attachment` returns the image itself, only for the current conversation. It needs the session token, so pages fetch it and show it from a blob. Served inline with `nosniff` and a `sandbox` Content-Security-Policy.

### 3.11 `POST /widget/conversation/typing`

No body; `204`. Tells the agent the customer is typing (event `typing`). The chat sends it at most every 3 seconds while the customer types, once a person has the chat. `429 RATE_LIMITED` past 40 a minute.

### 3.9 `POST /widget/conversation/new`: "Start a new chat"

No request body. Closes the current conversation, whoever has it (event `RESOLVED`, reason `CUSTOMER`, so agents see "Customer started a new chat"), and starts a new one with the greeting. Nothing is deleted: the old one stays in the team's history. A guest's email and name carry over, so they aren't asked again.

```json
// 201
{ "conversation": Conversation, "messages": [Message] }   // just the greeting
```

Subscribe to the new conversation's topic from here on. `403 CONTACT_BLOCKED` for a blocked contact; `429 RATE_LIMITED` shares the message limit.

---

## 4. Agent endpoints

All require an agent token. The agent only sees tenants they're a member of (`tenant_agents`).

### 4.1 `GET /agent/tenants`

```json
200 { "items": [{ "id": "…", "slug": "demo", "displayName": "Demo Company", "attachments": true, "primaryColor": "#0051fb" }] }
// attachments: agents can send images in this company's chats; primaryColor: the company's colour (or null)
```

### 4.2 `GET /agent/conversations`

| Query | Values |
|---|---|
| `tenant` | slug (optional; default: all my tenants) |
| `status` | `WAITING`, `HUMAN`, `RESOLVED` (required) |
| `assignee` | `me` (optional) |
| `limit` | default 25, max 200 |
| `cursor` | `nextCursor` from the previous page |
| `q` | search, max 100 characters (optional): matches the customer's name or email, or any message in the conversation, ignoring case. `%` and `_` are taken literally |

`WAITING` is sorted by priority (URGENT first), then oldest first. The others are newest first. Paging continues from the last row shown (keyset), so new conversations arriving meanwhile don't shift pages. `nextCursor` is `null` on the last page. A bad cursor is `400 VALIDATION_ERROR`.

```json
200 { "items": [ConversationSummary, …], "nextCursor": "MnwyMDI2LTEwLTI5VDEw…" }
```

### 4.3 `GET /agent/conversations/{id}`

```json
200
{
  "conversation": Conversation,
  "messages": [Message, …],      // latest 100
  "events": [ConversationEvent, …],
  "notes": [Note, …]             // agent-only, oldest first
}
```

`conversation.customer` includes `contactId` and `blocked` when the customer left their details.

### 4.4 `POST /agent/conversations/{id}/accept`

`WAITING → HUMAN`, assigns the caller. `200 { "conversation": Conversation }`. Errors: `409 ALREADY_ACCEPTED`, `409 INVALID_TRANSITION`.

### 4.5 `POST /agent/conversations/{id}/messages`

```json
{ "clientMessageId": "a-91c2", "body": "Hi Asha, I can help with that.", "replyTo": "0192…" }   // replyTo optional
// 201 { "message": Message }
```

Allowed only when the status is `HUMAN` and the caller is the assignee. Otherwise `403 NOT_ASSIGNEE` or `409 INVALID_TRANSITION`.

### 4.6 `POST /agent/conversations/{id}/resolve`

`HUMAN → RESOLVED` (assignee), or `WAITING → RESOLVED` (any agent of the tenant, for spam). `200 { "conversation": Conversation }`.

### 4.7 `POST /agent/conversations/{id}/return-to-bot`

`HUMAN → BOT` (assignee). The bot sends the menu again. `200 { "conversation": Conversation }`.

### 4.8 `GET /agent/me`

```json
200 { "sub": "agent-7", "name": "Ram", "tenants": ["demo"] }
```

Also remembers the agent's display name, so other agents can pick them in a transfer.

### 4.9 `POST /agent/conversations/{id}/reopen`

`RESOLVED → HUMAN`, assigned to the caller, e.g. to follow up. `200 { "conversation": Conversation }`. Errors: `403 CONTACT_BLOCKED`, `409 INVALID_TRANSITION`.

### 4.10 `GET /agent/conversations/{id}/agents`

Agents of the conversation's tenant: `200 { "items": [{ "sub": "agent-7", "name": "Ram" }, …], "nextCursor": null }`.

### 4.11 `POST /agent/conversations/{id}/transfer`

```json
{ "agentSub": "agent-9" }   // or {} to put it back in the queue
```

Assignee only, `HUMAN` only. To an agent: stays `HUMAN`, assigned to them. To the queue: `HUMAN → WAITING`, keeping its priority. `200 { "conversation": Conversation }`. Errors: `400 VALIDATION_ERROR` (not an agent of this tenant, or yourself), `403 NOT_ASSIGNEE`, `409 INVALID_TRANSITION`.

### 4.12 `POST /agent/conversations/{id}/block`

Blocks the conversation's contact and closes the conversation if it's open. The customer can't send messages or reach the team with that email until unblocked. Same rule as resolve: any agent while `WAITING`, the assignee while `HUMAN`. `200 { "conversation": Conversation }`. Errors: `409 NO_CONTACT`.

### 4.13 `POST /agent/contacts/{contactId}/unblock`

`204`. `404 CONTACT_NOT_FOUND` if the contact isn't in one of the caller's tenants.

### 4.14 `POST /agent/conversations/{id}/notes`

```json
{ "body": "Called them back, waiting on a reply." }
// 201 { "note": Note }
```

Any agent of the tenant. Notes are never sent to the customer.

```ts
interface Note { id: string; author: { sub: string; name: string }; body: string; createdAt: string }
```

### 4.17 `GET /agent/conversations/{id}/history`

The same customer's other conversations with this tenant, newest first, at most 20: matched by their contact (email) or, for logged-in customers, their `sub`. Anonymous guests have none.

```json
200 { "items": [ConversationSummary, …], "nextCursor": null }
```

### 4.16 `POST /agent/conversations/{id}/read`

```json
{ "messageId": "0192…" }   // the latest message the agent has seen
// 204
```

Per agent. Marking an older message changes nothing. `ConversationSummary.unreadCount` counts the customer messages after this one (all of them if the agent never opened the conversation).

### 4.15 `GET /agent/conversations/counts`

`200 { "waiting": 3, "mine": 1, "myUnread": 2 }`: waiting conversations across the caller's tenants, the caller's open (`HUMAN`) chats, and unread customer messages in them. For badges, so the console doesn't have to load whole lists to count them.

### 4.18 `GET /agent/conversations/{id}/replies`

Saved replies of the conversation's tenant, by title. The console fills in `{name}` itself.

```json
200 { "items": [{ "id": "…", "title": "Refunds", "body": "Hi {name}, refunds take 5 days." }], "nextCursor": null }
```

### 4.21 `POST /agent/presence`

```json
{ "status": "AWAY" }          // or "ONLINE"; no body keeps the current status (ONLINE the first time)
// 200 { "status": "AWAY" }
```

The console calls it every minute while it's open. An agent is online while their status is `ONLINE` and they checked in within the last 2 minutes, so closing the console makes them offline without a call. Signing out sets `AWAY`. `GET /agent/conversations/{id}/agents` and `GET /admin/tenants/{slug}/agents` include `online` for each agent.

### 4.20 `POST /agent/conversations/{id}/typing`

No body; `204`. The customer sees "<agent name> is typing…" (event `typing`). The console sends it at most every 3 seconds while the agent types a reply (not a note).

### 4.19 Images

`POST /agent/conversations/{id}/attachments` (multipart: `file`, optional `clientMessageId` and `replyTo`) → `201 { "message": Message }`. Same rules as a reply (the assignee of an accepted chat) and the same checks as 3.10. `GET /agent/conversations/{id}/messages/{messageId}/attachment` returns the image to any agent of the tenant.

---

## 4b. Admin endpoints

For people with the admin role (`QUOKRA_ADMIN`, set with `QUOKRA_ADMIN_ROLE`, in the same roles claim as the agent role). Same token as the agent API; everything else gets `403`. The console shows these under **Settings** (the gear icon in its header).

```ts
interface Tenant {
  id: string; slug: string; displayName: string;
  publishableKey: string;                // pk_live_…, public
  widgetOrigins: string[];               // sites that may show the chat
  consoleOrigins: string[];              // dashboards that may show the console
  logoUrl: string | null; primaryColor: string | null; botConfig: string;
  autoResolveMinutes: number;            // close quiet chats after this long; 0 = never (default 1440)
  attachmentsEnabled: boolean;           // customers and agents may send images (default false)
  retentionMonths: number;               // resolved chats are deleted after this long; 0 = forever (default 12)
  alertEmail: string | null;             // where "a chat is waiting" emails go (comma-separated, up to 5); null = agents' own emails
  waitingAlertMinutes: number;           // email the team after a chat waited this long with nobody online; 0 = never (default 10)
  emailCustomers: boolean;               // email a customer an agent's reply they haven't answered in 5 minutes (default false)
  openingHours: { timezone: string; days: { [day: string]: string } } | null;
                                         // e.g. { "timezone": "Europe/Berlin", "days": { "MON": "09:00-17:00" } };
                                         // days MON..SUN, left out = closed; null = always open
  hasSecret: boolean;                    // the secret itself is never returned again
  agentCount: number; inviteCount: number; createdAt: string;
}
```

| Endpoint | What it does |
|---|---|
| `GET /admin/mail` | Whether the server can send email: `{ "configured": true, "ok": true, "problem": null }`. Emails go out only while `ok` |
| `GET /admin/retention` | What companies may choose for `retentionMonths`: `{ "minMonths": 1, "maxMonths": 60 }`; `maxMonths: 0` means 0 (forever) is allowed too. Anything else is `400 VALIDATION_ERROR` |
| `GET /admin/storage` | Whether image storage works: `{ "ok": true, "store": "disk", "problem": null }`. Checked by writing and reading a small file, at most every 5 minutes |
| `GET /admin/bots` | Bot files the server has: `{ "items": ["demo", …] }` |
| `GET /admin/tenants` | All tenants: `{ "items": [Tenant, …] }` |
| `POST /admin/tenants` | Create one: `{ slug, displayName, widgetOrigins, consoleOrigins, logoUrl?, primaryColor?, botConfig, autoResolveMinutes?, openingHours? }` → `201 Tenant` with a new publishable key. `409 SLUG_TAKEN` |
| `GET /admin/tenants/{slug}` | One tenant |
| `PUT /admin/tenants/{slug}` | Replace its settings (same body; the slug can't change) |
| `POST /admin/tenants/{slug}/secret` | New secret key for identifying customers: `{ "secretKey": "sk_…" }`, shown only this once; the old one stops working. `409 SECRETS_DISABLED` without `TENANT_SECRET_ENCRYPTION_KEY` |
| `GET /admin/tenants/{slug}/agents` | `{ "agents": [{ sub, name, email }], "invites": [{ email, createdAt }] }` |
| `POST /admin/tenants/{slug}/agents` | `{ "email": "…" }` → `{ "result": "ADDED" }` if someone already signed in with that verified email, else `"INVITED"` |
| `DELETE /admin/tenants/{slug}/agents/{sub}` | Remove an agent (`204`) |
| `DELETE /admin/tenants/{slug}/invites/{email}` | Cancel an invite (`204`) |
| `GET /admin/tenants/{slug}/bot` | `{ "custom": false, "file": "demo", "bot": { greeting, teaser?, menu: [{ id, title, keywords, answer }], human, urgent, fallback, max_fallbacks, handoff_message, texts } }`: the same fields as a bot file. `custom` is true once edited here |
| `PUT /admin/tenants/{slug}/bot` | Save the company's own bot (body: the `bot` object above). Checked with the same rules as bot files; `400 VALIDATION_ERROR` names the field, e.g. `bot: menu needs at least one item`. Used for new messages at once |
| `DELETE /admin/tenants/{slug}/bot` | Drop the edited bot and use the bot file again |
| `GET /admin/tenants/{slug}/replies` | Saved replies: `{ "items": [{ id, title, body }] }` |
| `POST /admin/tenants/{slug}/replies` | `{ title, body }` → `201 { id, title, body }`. Title up to 80 characters, body up to 2,000; `{name}` in the body becomes the customer's name |
| `PUT /admin/tenants/{slug}/replies/{id}` | Change one (same body). `404 REPLY_NOT_FOUND` |
| `DELETE /admin/tenants/{slug}/replies/{id}` | Remove one (`204`). `404 REPLY_NOT_FOUND` |

Validation (`400 VALIDATION_ERROR`, the message names the field): slug of 2–40 lowercase letters, digits or dashes; sites as plain origins (`https://www.example.com`, no path); colour `#rrggbb`; logo `https://`; an existing bot file; opening hours with a real time zone (`Europe/Berlin`), `HH:mm-HH:mm` ranges that close after they open, and at least one day.

**Invites:** an invited agent joins on their first `GET /agent/me` whose token has that `email` with `email_verified: true`. Unverified emails never match.

`GET /agent/me` also says what the caller may use: `{ …, "agent": true, "admin": true }`. Admins without the agent role only get Settings.

---

## 5. Realtime events

STOMP over WebSocket at `wss://chat.<domain>/ws`.

| Destination | Subscribers |
|---|---|
| `/topic/conversations/{conversationId}` | The conversation's customer session; agents of its tenant |
| `/topic/tenants/{tenantId}/queue` | Agents of that tenant |
| `/topic/conversations/{conversationId}/notes` | Agents of its tenant (never customers) |

Every event has this envelope:

```json
{ "type": "message.created", "conversationId": "…", "at": "2026-10-29T08:15:00Z", "data": { … } }
```

| Type | Topic | `data` |
|---|---|---|
| `message.created` | conversation | `Message` |
| `conversation.updated` | conversation, queue | `Conversation` (customers get the customer view) |
| `conversation.waiting` | queue | Agent snapshot of the conversation (as `ConversationSummary`, without `lastMessagePreview`; `tenantId` instead of `tenantSlug`) |
| `note.created` | notes | `Note` |
| `typing` | conversation | `{ "senderType": "CUSTOMER" \| "AGENT", "name": "Sam" \| null }`: someone is typing. Not stored; show it for about 5 s, and drop it when their message arrives. Both sides get every one, so ignore your own |
| `conversation.activity` | queue | `{ id, lastMessagePreview, lastMessageAt, senderType, assigneeSub, customerName }` for every new message, so lists update their preview and unread count live, and the assigned agent is notified |
| `conversation.transferred` | queue | `{ id, toSub, toName, byName, customerName }`: a chat was handed to an agent |
| `note.added` | queue | `{ id, authorSub, authorName, assigneeSub, customerName, preview }`: a private note on a chat (the note itself goes to the notes topic) |
| `typing` | conversation | `{ "senderType": "AGENT" \| "CUSTOMER", "on": true }` |

Typing is sent by clients to `/app/conversations/{id}/typing` with `{ "on": true }`; the server relays it. It is never stored. *(Not built yet: until then the server rejects any client `SEND` over the socket.)*

Rules enforced today:

- `CONNECT` needs `Authorization: Bearer <chat session token>`; otherwise the server answers with an `ERROR` frame and closes.
- A customer session may only subscribe to its own conversation's topic.
- `CONNECT` also accepts an agent access token (same checks as the agent REST API). Agents may subscribe to `/topic/tenants/{tenantId}/queue` and `/topic/conversations/{id}` for their own tenants only.
- On the queue topic, `conversation.updated` carries the agent snapshot too (accept, resolve, return to bot, reopen).
- Events are sent only after the change is committed. Heartbeats every 10 s both ways.

---

## 6. Shared types

```ts
type Status = "BOT" | "WAITING" | "HUMAN" | "RESOLVED";
type Priority = "NORMAL" | "HIGH" | "URGENT";
type SenderType = "CUSTOMER" | "BOT" | "AGENT" | "SYSTEM";
type HandoffReason = "CUSTOMER_REQUEST" | "FALLBACK_LIMIT" | "URGENT" | "BOT_ERROR";

interface Conversation {
  id: string;
  status: Status;
  priority: Priority;          // agent view only
  handoffReason: HandoffReason | null;  // agent view only
  assignee: { name: string } | null;
  asking: "EMAIL" | "NAME" | null; // customer view only: what the bot is asking for
  customer: { sub: string | null; contactId: string | null; name: string | null; email: string | null } | null; // agent view only
  createdAt: string;
  lastMessageAt: string;
  resolvedAt: string | null;
}

interface ConversationSummary {
  id: string;
  tenantSlug: string;
  status: Status;
  priority: Priority;
  handoffReason: HandoffReason | null;
  customerName: string | null;  // null = guest
  lastMessagePreview: string;   // first 120 chars
  lastMessageAt: string;
  waitingSince: string | null;
  assignee: { sub: string; name: string } | null;
  unreadCount: number;          // customer messages this agent hasn't read
}

interface Message {
  id: string;
  senderType: SenderType;
  senderName: string | null;    // agent name, or bot display name
  body: string;                 // plain text
  options: { id: string; title: string }[] | null;   // menu buttons
  clientMessageId: string | null;
  createdAt: string;
  attachment: { name: string; contentType: string; size: number } | null;   // an image; body is "Sent an image"
  replyTo: { id: string; senderType: SenderType; senderName: string | null; preview: string } | null;
                                // the message this answers, copied when sent; preview is its first line or "Image"
}

interface ConversationEvent {
  id: string;
  type: "CREATED" | "HANDOFF" | "ACCEPTED" | "RESOLVED" | "RETURNED_TO_BOT" | "REOPENED" | "IDENTIFIED";
  actorType: SenderType;
  actorName: string | null;
  reason: string | null;                  // e.g. "URGENT" for a handoff
  createdAt: string;
}
```

Customers never receive `priority`, `handoffReason`, customer details of others, or agent `sub`s.

---

## 7. postMessage: customer chat

Every message: `{ "source": "quokra", "type": "...", ...fields }`. Receivers check `event.origin`, `event.source` and `source`.

| Direction | Type | Fields | Meaning |
|---|---|---|---|
| panel → host | `chat.ready` | `color` | Panel loaded; `color` is the tenant's primary colour for the bubble (used only if it's a plain `#rrggbb`) |
| panel → host | `chat.close` | | User closed the panel |
| panel → host | `chat.unread` | `count` | Unread messages while closed |
| host → panel | `chat.open` / `chat.close` | | Open or close from the host |
| host → panel | `chat.identify` | `token` | Identity token from the host backend |
| host → panel | `chat.logout` | | Forget the customer's session and start a new guest session |
| host → panel | `theme.set` | `theme` | `light`, `dark` or `auto` (the console accepts it too) |
| panel → host | `chat.identified` | | Identify worked |
| panel → host | `chat.identify.failed` | `code` | Identify failed, e.g. `IDENTITY_TOKEN_EXPIRED` |

The host side (`sdk.js`) only accepts messages from the Quokra origin. The panel only sends `chat.*` messages to the parent and never sends tokens out.

---

## 8. postMessage: agent console

| Direction | Type | Fields | Meaning |
|---|---|---|---|
| console → host | `console.ready` | | Console loaded, waiting for a token |
| console → host | `auth.request` | `reason` (`initial`, `expired`, `unauthorized`) | Please send a fresh agent token |
| host → console | `auth.token` | `token` | Agent access token |
| console → host | `console.unread` | `count` | Waiting conversations (for a nav badge) |
| console → host | `console.color` | `color` | The company's colour, used by a console embedded with `tenant` (unless `accent` is set); `sdk.js` paints the floating button with it |
| console → host | `console.close` | | The agent closed the drawer (bubble mode) |
| console → host | `console.notify` | `kind`, `title`, `body`, `conversationId` | Something to tell the agent; `sdk.js` shows it as a browser notification if allowed and the console isn't in front |
| console → host | `console.notifications.check` / `.request` | | Ask for the notification status / ask the browser for permission (from a click) |
| host → console | `console.notifications` | `available`, `permission` | Whether the dashboard turned notifications on, and the browser's permission |
| host → console | `console.open` | `conversationId` | Show this conversation (after a notification was clicked) |
| console → host | `console.expand` | `conversationId` (or `null`) | The agent wants the full view (bubble mode, only when the host gave `fullView`) |

`/console?tenant=slug` can only be framed by that tenant's `console_origins` (`frame-ancestors`), and the console only accepts `auth.token` from its parent window. `sdk.js` sends tokens only to the Quokra origin, and only to its own frame.

---

## 9. sdk.js JavaScript API

```html
<script src="https://chat.<domain>/sdk.js" async></script>
```

### 9.1 Customer chat: `window.Quokra.chat`

| Call | Purpose |
|---|---|
| `Quokra.chat.init({ slug, pk, mode?, container?, position?, theme?, accent? })` | Mount the chat. `theme`: `"host"` (default: follows your page's light/dark mode, also when it changes), `"light"`, `"dark"` or `"auto"` (the visitor's system setting). `accent`: `#rrggbb` brand colour instead of the tenant's. `mode`: `"bubble"` (default), `"inline"`, `"popup"`. `container`: element or selector for inline. `position`: `"right"` (default) or `"left"` |
| `Quokra.chat.open()` / `Quokra.chat.close()` | Open or close the panel |
| `Quokra.chat.logout()` | The customer logged out of your site: the chat forgets them and starts over as a guest. Call it on logout, especially on shared computers |
| `Quokra.chat.setTheme(theme)` | Change the theme, e.g. when your site's dark mode is toggled |
| `Quokra.chat.identify(token)` | Identify the customer (token from the host backend). Can be called before the panel has loaded; it's sent once the panel is ready |
| `Quokra.chat.on(event, handler)` | Events: `ready`, `open`, `close`, `unread` (count), `identified`, `identifyfailed` (error code), `unavailable` (the chat didn't load within 15 seconds) |
| `Quokra.chat.destroy()` | Remove bubble and iframe |

Nothing is shown until the Quokra page inside the frame says it's ready, so a site never shows a broken frame when Quokra is down or the key is wrong: the bubble and console button stay hidden, `open()` waits, and `unavailable` fires after 15 seconds. Escape on the host page closes an open chat or console drawer.

Declarative alternatives, no JavaScript needed:

```html
<script src="https://chat.<domain>/sdk.js" data-quokra-chat="demo" data-quokra-pk="pk_live_…" async></script>
<div data-quokra-chat-inline="demo" data-quokra-pk="pk_live_…"></div>
<button data-quokra-chat-trigger="demo" data-quokra-pk="pk_live_…">Chat with us</button>
```

### 9.2 Agent console: `window.Quokra.console`

| Call | Purpose |
|---|---|
| `Quokra.console.mount(target, { tenant, getToken, height?, theme?, accent?, conversation?, notifications? })` | **Full-page mode:** mount the console in `target`. `getToken`: function returning a fresh agent access token (or a Promise of one). `tenant`: slug, used to check which sites may frame the console |
| `Quokra.console.init({ mode: "bubble", tenant, getToken, position?, theme?, accent?, fullView?, notifications? })` | **Bubble mode:** floating button with the waiting-chats badge; opens the console in a side drawer. `fullView`: your full-page console, as a URL or a function `(conversationId) => …`; the drawer then shows an "Open full view" button. `conversation` (mount): a conversation id to open first |
| `Quokra.console.open()` / `Quokra.console.close()` | Open or close the drawer (bubble mode) |
| `Quokra.console.setTheme(theme)` | Change the theme (`"host"`, `"light"`, `"dark"`, `"auto"`) |
| `Quokra.console.on(event, handler)` | Events: `ready`, `unread` (waiting chats + unread in my chats), `open`, `close`, `expand` (conversation id), `notification` (`{ kind, title, body, conversationId }`, for your own UI), `unavailable` (didn't load within 15 seconds). `notifications: true` lets the console show browser notifications |
| `Quokra.console.unmount()` | Remove it |

---

## 10. UI URLs

| URL | What |
|---|---|
| `/sdk.js` | Embed loader |
| `/w/{slug}?pk={pk}&mode={bubble\|inline\|popup\|full}` | Customer chat panel (`full` = whole page, e.g. a mobile WebView) |
| `/console?tenant={slug}` | Agent console (expects a token via postMessage in v1) |

---

## 11. Errors

Envelope:

```json
{ "error": { "code": "INVALID_TRANSITION", "message": "Conversation is RESOLVED", "details": { "from": "RESOLVED", "to": "HUMAN" } } }
```

| HTTP | Code | When |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Bad or missing fields (`details` lists them) |
| 401 | `UNAUTHORIZED` | Missing, invalid or expired token |
| 401 | `IDENTITY_TOKEN_INVALID` / `IDENTITY_TOKEN_EXPIRED` | Identify failed |
| 403 | `FORBIDDEN` | Valid token, but no access (role, tenant membership, other customer's conversation) |
| 403 | `CONTACT_BLOCKED` | The customer's contact was blocked by an agent |
| 403 | `NOT_ASSIGNEE` | Agent action on a conversation assigned to someone else |
| 403 | `ORIGIN_NOT_ALLOWED` | Request origin not allowed for the tenant |
| 404 | `TENANT_NOT_FOUND` / `CONVERSATION_NOT_FOUND` / `REPLY_NOT_FOUND` | Unknown id or key |
| 404 | `NOT_FOUND` | Unknown path |
| 405 | `METHOD_NOT_ALLOWED` | Wrong HTTP method for the path |
| 409 | `CONFLICT` | Someone else changed the same thing at the same moment; reload and retry |
| 409 | `INVALID_TRANSITION` | State change not allowed |
| 409 | `ALREADY_ACCEPTED` | Another agent accepted first |
| 409 | `NEWER_CHAT_OPEN` | Reopening an old chat while the customer has a newer open one (after "Start a new chat"); answer in that one |
| 409 | `NO_OPEN_CONVERSATION` | Sending without an open conversation |
| 422 | `MESSAGE_TOO_LONG` | Body over 2,000 characters |
| 403 | `ATTACHMENTS_OFF` | Images aren't allowed for this company |
| 404 | `ATTACHMENT_NOT_FOUND` | No image for that message in this conversation |
| 413 | `FILE_TOO_LARGE` | Image over 5 MB |
| 415 | `UNSUPPORTED_FILE` | Not a PNG, JPEG, GIF or WebP |
| 503 | `STORAGE_UNAVAILABLE` | Image storage isn't working |
| 429 | `RATE_LIMITED` | Too many requests (`Retry-After` header set): 5 new chat sessions a minute per IP address, 20 customer messages a minute per session |
| 500 | `INTERNAL_ERROR` | Unexpected; logged with the request id |
