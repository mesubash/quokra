# Development

How to work on this repository: setup, layout, conventions, and what "done" means. Commands marked *(planned)* become real as the build steps in the [Plan](PLAN.md#6-v1-build-steps) land.

**Other docs:** [Plan](PLAN.md) · [Architecture](ARCHITECTURE.md) · [API](API.md) · [Embedding guide](EMBEDDING.md)

---

## 1. Prerequisites

| Tool | Version |
|---|---|
| Java (JDK) | 21 |
| Maven | via the wrapper (`./mvnw`) |
| Node.js | 24 LTS |
| pnpm | 10 |
| Docker + Compose | recent |

---

## 2. Repository layout

```text
support-agent-platform/
├── README.md
├── docs/                    PLAN, ARCHITECTURE, API, EMBEDDING, DEVELOPMENT
├── server/                  Spring Boot 4.1 (Maven): API, realtime, bot; serves the built web UI
│   └── compose.yaml         PostgreSQL for local development
├── web/                     React + TypeScript (Vite): chat panel, console, sdk.js
└── test-host/               static pages on another port, for embedding tests
```

`notes/` (git-ignored) holds local research and is not part of the project.

---

## 3. Running locally *(planned)*

```bash
cd server && ./mvnw spring-boot:run  # starts PostgreSQL from compose.yaml, API on http://localhost:8080
cd web && pnpm install && pnpm dev   # UI with hot reload on http://localhost:5173 (proxies /api, /ws to :8080)
cd test-host && python3 -m http.server 5500   # host pages on http://localhost:5500
```

Then open `http://localhost:5500/` (a page from a different origin that embeds the chat and the console).

### Local agent login

v1 validates agent tokens from an OIDC provider. For local work, the `dev` Spring profile accepts tokens signed with a local key, and `test-host/console.html` gets one from a small dev endpoint. The `dev` profile must never be enabled in production; the server refuses to start with it when `APP_ENV=production`.

### Environment

| Variable | Used by | Local default |
|---|---|---|
| `DATABASE_URL` | server | `jdbc:postgresql://localhost:5432/support` |
| `DATABASE_USER` / `DATABASE_PASSWORD` | server | `support` / `support` |
| `OIDC_ISSUER_URI`, `OIDC_AUDIENCE` | server | unset with the `dev` profile |
| `SESSION_SIGNING_KEY` | server | dev value in `application-dev.yml` |
| `TENANT_SECRET_ENCRYPTION_KEY` | server | dev value in `application-dev.yml` |
| `PUBLIC_BASE_URL` | server | `http://localhost:8080` |

Real secrets never go in the repo; use `.env` files (git-ignored) or the deployment's secret store.

---

## 4. Conventions

### 4.1 Git

- `main` is always releasable. Work on branches: `feat/<topic>`, `fix/<topic>`, `chore/<topic>`, `docs/<topic>`.
- Commit messages: [Conventional Commits](https://www.conventionalcommits.org/) (`feat(server): add conversation state machine`), present tense, explaining *why* when it isn't obvious.
- Small commits; one concern per commit.

### 4.2 Server (Java)

- Package by feature (see [Architecture §3](ARCHITECTURE.md#3-server-internals)).
- Controllers are thin: validate input, call one service method, map to a DTO.
- All state changes go through `ConversationService` → `StateMachine`.
- No business logic in entities beyond simple invariants.
- Records for DTOs; Bean Validation on request bodies.
- Database changes only through Flyway migrations (`V<n>__<description>.sql`); never edit an applied migration.
- Formatting: the project's formatter config; no unused imports or dead code.

### 4.3 Web UI (TypeScript)

- TypeScript strict mode.
- API types mirror [API.md §6](API.md#6-shared-types); keep them in `lib/types.ts`.
- `sdk.js` is plain ES2017 JavaScript with no dependencies and no build step, so hosts can load it as is.
- Never render message bodies as HTML.

### 4.4 Docs

- When behaviour changes, update the matching doc in the same change.
- `API.md` is the contract; code and doc must agree.

---

## 5. Testing

| What | Tool | Where |
|---|---|---|
| Server unit tests | JUnit 5 | `server/src/test/…` |
| Server integration tests | Spring Boot Test + Testcontainers (PostgreSQL) | `server/src/test/…` |
| Web UI | Vitest + Testing Library | `web/**/*.test.ts(x)` |
| Embedding (manual) | `test-host/` pages | Before each release |

Commands *(planned)*:

```bash
cd server && ./mvnw verify
cd web && pnpm test
```

---

## 6. Definition of done (for each build step)

1. The "done when" check in the [Plan](PLAN.md#6-v1-build-steps) passes.
2. Tests for the new logic exist and pass locally.
3. Docs updated if behaviour or API changed.
4. No secrets, debug code or TODOs without an issue.
5. Committed with a clear message.
