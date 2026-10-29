# Quokra

An embeddable customer support chat: a FAQ bot answers first, and a human agent takes over in the same conversation when needed. The customer chat and the agent console are both embedded with one script tag.

> Status: planning done, v1 under construction.

## Docs

| Doc | What's in it |
|---|---|
| [Plan](docs/PLAN.md) | Goals, versions, v1 scope and build steps, open decisions |
| [Architecture](docs/ARCHITECTURE.md) | Components, flows, state machine, data model, security, deployment, decisions |
| [API](docs/API.md) | REST endpoints, realtime events, postMessage protocol, `sdk.js` API, errors |
| [Embedding guide](docs/EMBEDDING.md) | How hosts add the chat and the console |
| [Development](docs/DEVELOPMENT.md) | Setup, conventions, testing, definition of done |

## Stack

Spring Boot 4.1 (Java 21) + PostgreSQL · React + TypeScript (Vite), served by the Spring Boot server · STOMP over WebSocket.

## Repository

```text
server/      Spring Boot: API, realtime, bot, serves the web UI
web/         React app: customer chat panel, agent console, sdk.js
test-host/   pages on another origin for embedding tests
docs/        plan, architecture, API, embedding, development
```
