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
quokra/
├── README.md
├── docs/                    PLAN, ARCHITECTURE, API, EMBEDDING, DEVELOPMENT
├── server/                  Spring Boot 4.1 (Maven): API, realtime, bot; serves the built web UI
│   └── compose.yaml         PostgreSQL for local development
├── web/                     React + TypeScript (Vite): chat panel, console, sdk.js
├── e2e/                     Playwright: the whole chat flow in a browser
└── test-host/               static pages on another port, for embedding tests
```

`notes/` (git-ignored) holds local research and is not part of the project.

---

## 3. Running locally

```bash
cd web && pnpm install && pnpm build   # builds the UI into the server
cd server && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # starts PostgreSQL, loads the demo tenant, http://localhost:8080
cd web && pnpm dev   # optional: UI with hot reload on http://localhost:5173 (proxies /api, /ws to :8080); stop the playground first, it uses the same port
cd test-host && python3 -m http.server 5500   # host pages on http://localhost:5500
```

The dev server and the Docker demo stack (`docker compose up` at the repo root, see [Deploying](DEPLOY.md)) use the same ports (8080, 8180): stop one before starting the other (`docker compose down` at the root).

Then open `http://localhost:5500/` (a page from a different origin that embeds the chat and the console).

### Local agent login

Agents log in through an OIDC provider. Locally there are two ways, and both work at the same time:

**1. Keycloak (real login).** `compose.yaml` starts Keycloak on `http://localhost:8180` together with PostgreSQL, importing the dev realm from `server/keycloak/quokra-realm.json`, and the `dev` profile trusts it. Users (dev only, the passwords are public):

| User | Password | Role |
|---|---|---|
| `sam` | `sam` | `SUPPORT_AGENT` and `QUOKRA_ADMIN` (sees Settings), member of the demo tenant |
| `alex` | `alex` | `SUPPORT_AGENT`, member of the demo tenant |
| `riley` | `riley` | none (refused by Quokra) |

The client `quokra-dashboard` is public with PKCE, for apps on `localhost:5173` (playground) and `localhost:5500` (test host). The Keycloak admin console is at `http://localhost:8180/admin` (`admin` / `admin`). To get a token from the command line (direct grants are on for dev only):

```bash
curl -s -X POST localhost:8180/realms/quokra/protocol/openid-connect/token \
  -d grant_type=password -d client_id=quokra-dashboard -d username=sam -d password=sam
```

**2. Dev tokens (quick, no login page).** The `dev` profile also turns on `quokra.dev.agent-tokens`: the server signs agent tokens itself with a key made at startup.

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
| `TENANT_SECRET_ENCRYPTION_KEY` | server | dev profile: a fixed dev-only key. Elsewhere: 32 random bytes, base64 (`openssl rand -base64 32`); unset turns identify off |
| `PUBLIC_BASE_URL` | server | step 6 |
| `FORWARD_HEADERS_STRATEGY` | server | `native`: trusts `X-Forwarded-For` only from proxies on private networks, so rate limits see the real client. Use `none` if the server faces the internet directly |

### Tenant secret keys

Each tenant's backend signs identity tokens with the tenant's secret key. Create or replace it with:

```bash
./mvnw -q spring-boot:run -Dspring-boot.run.arguments="tenant-secret demo"
# or, from the image / jar: java -jar quokra-server.jar tenant-secret demo
```

It prints the new key once and exits; the old key stops working. Give it to the tenant over a secure channel. Locally, the `demo` tenant already has a dev-only key (`db/dev/V8_1`), used by `test-host/identify.html`.

Real secrets never go in the repo; use `.env` files (git-ignored) or the deployment's secret store.

---

## 4. Conventions

### 4.1 Git

- `main` holds released versions only; the public demo deploys from it, and real deployments run a version tag.
- `develop` is where work comes together. Branch from it (`feat/<topic>`, `fix/<topic>`, `chore/<topic>`,
  `docs/<topic>`) and merge back into it. CI runs the server, web and end-to-end tests on every push and pull request.
- An urgent fix for a released version: branch `fix/<topic>` from `main`, release it as a patch (below), and
  merge `main` back into `develop`.
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
| End to end | Playwright, against the Docker image | `e2e/tests/…` (see `e2e/README.md`) |
| Embedding (manual) | `test-host/` pages | Before each release |

Commands (CI runs the same, see `.github/workflows/ci.yml`):

```bash
cd server && ./mvnw verify
cd web && pnpm test
cd e2e && pnpm test        # needs Quokra running, see e2e/README.md
```

---

## 6. Releasing

Versions follow [SemVer](https://semver.org/): `2.1.0` for new features, `2.0.1` for fixes. Each release:

1. On `develop`: add the version's heading to [CHANGELOG.md](CHANGELOG.md) (what people notice), and set the
   version in `server/pom.xml` and `web/package.json`.
2. Merge `develop` into `main` (fast-forward) and push.
3. Tag and push: `git tag v2.1.0 && git push origin v2.1.0`. The release workflow checks the tag matches
   `server/pom.xml` and publishes `ghcr.io/mesubash/quokra:2.1.0` (also `:2.1` and `:latest`).
4. Create the GitHub Release for the tag, with the changelog section as notes:
   `gh release create v2.1.0 --title "Quokra 2.1.0" --notes-file <notes>`.

Deployments pin a version (`ghcr.io/mesubash/quokra:2.1.0`), never `:latest`, and upgrade on purpose: read the
changelog, back up the database (migrations run on start), then change the tag.

---

## 7. Definition of done (for each build step)

1. The "done when" check in the [Plan](PLAN.md#6-v1-build-steps) passes.
2. Tests for the new logic exist and pass locally.
3. Docs updated if behaviour or API changed.
4. No secrets, debug code or TODOs without an issue.
5. Committed with a clear message.
