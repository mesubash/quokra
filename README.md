<p align="center">
  <img src="web/public/logo.png" width="96" height="96" alt="Quokra logo" />
</p>

<h1 align="center">Quokra</h1>

<p align="center">
  An embeddable support chat: a FAQ bot answers first, and a person on your team takes over in the same conversation.<br />
  The customer chat and the agent console both go into your site or dashboard with one script tag.
</p>

---

## What it does

**For customers**
- A chat bubble, inline panel or popup on any website, in your brand colour and wording.
- A FAQ bot with a menu and keywords, answering common questions straight away.
- "Talk to a person" (or an urgent keyword, or the bot not understanding twice) hands the chat to your team. Guests are asked for their email and name in the chat first, so the team can follow up.
- Logged-in customers can be identified by your backend, and keep one conversation across devices.

**For agents**
- A console for your dashboard: full page, or a drawer opened from a floating button, with "open full view".
- Queue, my chats and resolved lists with unread counts, search, and live updates.
- Reply (which also accepts the chat), private notes, transfer to a colleague or back to the queue, return to the bot, resolve, reopen, block a contact.
- A sound for new chats.

**For the site embedding it**
- Follows your page's light or dark mode by itself, or a theme you choose, and your own accent colour.
- Works on any stack: plain HTML, React, Next.js, Vue. No build step on your side.

## See it in a real app

[**quokra-playground**](https://github.com/mesubash/quokra-playground) is a sample dashboard with Quokra embedded the way a real product would use it. Sign in as a customer to chat, or as a demo agent (Sam or Alex) to answer from the Support page; open both side by side to see it live.

## Embed it

Customer chat, on any page:

```html
<script src="https://chat.example.com/sdk.js" data-quokra-chat="your-slug" data-quokra-pk="pk_live_…" async></script>
```

Agent console, in your dashboard (your login provides the agent's token):

```html
<div id="support" style="height: 100vh"></div>
<script src="https://chat.example.com/sdk.js"></script>
<script>
  Quokra.console.mount("#support", { tenant: "your-slug", getToken: () => auth.getAccessToken() });
</script>
```

Everything else (bubble mode, identifying customers, themes, events) is in the [embedding guide](docs/EMBEDDING.md).

## Run it locally

Needs Java 21+, Docker (for PostgreSQL) and pnpm.

```bash
cd web && pnpm install && pnpm build    # builds the chat and console into the server
cd ../server && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
# http://localhost:8080/console  (sign in as the dev agent)

# Optional: test pages on another origin
cd ../test-host && python3 -m http.server 5500
# http://localhost:5500
```

The dev profile starts PostgreSQL with Docker Compose and loads a demo tenant. More in [Development](docs/DEVELOPMENT.md).

## Status

v1 (FAQ bot + human handoff on the web) is feature complete and being tried in the playground. Left for v1: real agent login against an identity provider, tenant setup tooling, and the Docker image. See the [plan](docs/PLAN.md).

## Docs

| Doc | What's in it |
|---|---|
| [Plan](docs/PLAN.md) | Goals, versions, v1 scope and build steps, open decisions |
| [Architecture](docs/ARCHITECTURE.md) | Components, flows, state machine, data model, security, deployment, decisions |
| [API](docs/API.md) | REST endpoints, realtime events, postMessage protocol, `sdk.js` API, errors |
| [Embedding guide](docs/EMBEDDING.md) | How sites add the chat and the console |
| [Development](docs/DEVELOPMENT.md) | Setup, conventions, testing, definition of done |

## Stack

Spring Boot 4.1 (Java 21) + PostgreSQL · React + TypeScript (Vite), served by the Spring Boot server · STOMP over WebSocket.

```text
server/      Spring Boot: API, realtime, bot, serves the web UI
web/         React app: customer chat panel, agent console, sdk.js
test-host/   pages on another origin for embedding tests
docs/        plan, architecture, API, embedding, development
```

Third-party assets and their licences: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
