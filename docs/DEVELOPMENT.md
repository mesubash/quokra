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
cd web && pnpm install && pnpm build   # builds the UI into the server
cd server && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # starts PostgreSQL, loads the demo tenant, http://localhost:8080
cd web && pnpm dev   # optional: UI with hot reload on http://localhost:5173 (proxies /api, /ws to :8080)
cd test-host && python3 -m http.server 5500   # host pages on http://localhost:5500
```

Then open `http://localhost:5500/` (a page from a different origin that embeds the chat and the console).

### Local agent login

Agents normally log in through an OIDC provider (`OIDC_ISSUER_URI`). Locally, the `dev` profile turns on `quokra.dev.agent-tokens`: the server signs agent tokens itself with a key made at startup.

```bash
curl -X POST localhost:8080/dev/agent-token -H 'Content-Type: application/json' -d '{"sub":"dev-agent","name":"Sam"}'
# → { "accessToken": "eyJ..." }   (8 hours, role SUPPORT_AGENT)
```

`dev-agent` is a member of the demo tenant (dev seed). Never enable dev agent tokens in production: the server refuses to start with them when `APP_ENV=production`.

### Environment

| Variable | Used by | Local default |
|---|---|---|
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | server | not needed: Postgres starts from `compose.yaml` |
| `QUOKRA_SESSION_SIGNING_KEY` | server | unset: a random key is used (sessions reset on restart) |
| `QUOKRA_BOTS_DIR` | server | unset: only the built-in `demo` bot |
| `OIDC_ISSUER_URI` | server | unset: agent login only through dev tokens |
| `OIDC_AUDIENCE` | server | `quokra-api` |
| `QUOKRA_AGENT_ROLES_CLAIM` / `QUOKRA_AGENT_ROLE` | server | `roles` / `SUPPORT_AGENT` |
| `TENANT_SECRET_ENCRYPTION_KEY` | server | step 9 |
| `PUBLIC_BASE_URL` | server | step 6 |

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
