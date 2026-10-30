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
- A FAQ bot with a menu and keywords, answering common questions straight away. Edit it in the Admin tab, or keep it in a file.
- "Talk to a person" (or an urgent keyword, or the bot not understanding twice) hands the chat to your team. Guests are asked for their email and name in the chat first, so the team can follow up.
- Logged-in customers can be identified by your backend, and keep one conversation across devices.

**For agents**
- A console for your dashboard: full page, or a drawer opened from a floating button, with "open full view".
- Queue, my chats and resolved lists with unread counts, search, and live updates.
- Reply (which also accepts the chat), saved replies picked with `/`, private notes, transfer to a colleague or back to the queue, return to the bot, resolve, reopen, block a contact.
- Notifications: sounds, toasts and browser notifications for new chats, replies in my chats, transfers and notes.
- Quiet chats close by themselves after a time each company sets.
- Opening hours: outside them, customers are told when the team is back.

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

## Try it with Docker

Nothing to install but Docker:

```bash
docker compose up --build
```

Quokra, PostgreSQL and Keycloak start with a demo company and two demo agents (`sam` / `sam`, also an admin, and `alex` / `alex`). The customer chat is at http://localhost:8080/w/demo?pk=pk_test_demo; for the agent console, run the [playground](https://github.com/mesubash/quokra-playground) and sign in as an agent. To run it for real, see [Deploying](docs/DEPLOY.md).

## Run it for development

Needs Java 21+, Docker (for PostgreSQL and Keycloak) and pnpm.

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

v1 (FAQ bot + human handoff on the web) is complete: real agent login, an Admin tab for companies and keys, and a Docker image. v2 (daily operations) is in progress: notifications, auto-resolve, saved replies, opening hours and FAQ editing are done; a retention job and more come next. See the [plan](docs/PLAN.md).

## Docs

| Doc | What's in it |
|---|---|
| [Guides](docs/guides/README.md) | Step by step: get started, set up a company, add the chat and console, identify customers, connect your login, write the bot, notifications |
| [Plan](docs/PLAN.md) | Goals, versions, v1 scope and build steps, open decisions |
| [Architecture](docs/ARCHITECTURE.md) | Components, flows, state machine, data model, security, deployment, decisions |
| [API](docs/API.md) | REST endpoints, realtime events, postMessage protocol, `sdk.js` API, errors |
| [Embedding guide](docs/EMBEDDING.md) | How sites add the chat and the console |
| [Development](docs/DEVELOPMENT.md) | Setup, conventions, testing, definition of done |
| [Deploying](docs/DEPLOY.md) | The Docker image, settings, first steps after it runs |

## Stack

Spring Boot 4.1 (Java 21) + PostgreSQL · React + TypeScript (Vite), served by the Spring Boot server · STOMP over WebSocket.

```text
server/      Spring Boot: API, realtime, bot, serves the web UI
web/         React app: customer chat panel, agent console, sdk.js
test-host/   pages on another origin for embedding tests
docs/        plan, architecture, API, embedding, development
```

Third-party assets and their licences: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
