# Deploying Quokra

Quokra is one Docker image (the server with the web UI built in) plus a PostgreSQL database. Agents log
in through your own OIDC identity provider.

**Other docs:** [Plan](PLAN.md) · [Architecture](ARCHITECTURE.md) · [API](API.md) · [Embedding guide](EMBEDDING.md) · [Development](DEVELOPMENT.md)

---

## 1. Try it first

```bash
docker compose up --build
```

This starts Quokra, PostgreSQL and Keycloak with demo data: a company called "Quokra", agents
`sam` / `sam` (also an admin) and `alex` / `alex`. The customer chat is at
`http://localhost:8080/w/demo?pk=pk_test_demo`; for the agent console, run the
[playground](https://github.com/mesubash/quokra-playground) and sign in as an agent. Everything in that
stack (passwords, keys) is public: don't use it for real customers.

---

## 2. What you need

| | |
|---|---|
| Image | `docker build -t quokra .` from the repo root |
| Database | PostgreSQL 15 or newer, an empty database; Quokra creates its tables on start (Flyway) |
| Identity provider | Any OIDC provider for agents (Keycloak, Auth0, Okta, Entra ID, your own); see [Connect your identity provider](EMBEDDING.md#connect-your-identity-provider) |
| A domain with HTTPS | e.g. `chat.example.com`, in front of Quokra (reverse proxy or load balancer) |

---

## 3. Settings

All through environment variables. Generate random secrets with `openssl rand -base64 32`.

| Variable | Required | Example / notes |
|---|---|---|
| `SPRING_DATASOURCE_URL` | yes | `jdbc:postgresql://db:5432/quokra` |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | yes | |
| `PUBLIC_BASE_URL` | yes | `https://chat.example.com` (where Quokra is reached; the WebSocket checks it) |
| `QUOKRA_SESSION_SIGNING_KEY` | yes | 32+ random characters. Signs customers' chat sessions; changing it signs everyone out |
| `TENANT_SECRET_ENCRYPTION_KEY` | for identify | 32 random bytes, base64. Encrypts companies' secret keys; without it, identifying logged-in customers is off. Don't lose it: stored secrets can't be read without it |
| `OIDC_ISSUER_URI` | yes | Your provider's issuer, exactly as in tokens' `iss` |
| `OIDC_JWKS_URI` | sometimes | Only if the provider has no discovery document, or Quokra must reach it at another address than the issuer |
| `OIDC_AUDIENCE` | | Default `quokra-api` |
| `QUOKRA_AGENT_ROLES_CLAIM` | | Default `roles`; e.g. `realm_access.roles` |
| `QUOKRA_AGENT_ROLE` / `QUOKRA_ADMIN_ROLE` | | Defaults `SUPPORT_AGENT` / `QUOKRA_ADMIN` |
| `QUOKRA_BOTS_DIR` | | A folder with your bot files (`*.yml`), mounted into the container |
| `FORWARD_HEADERS_STRATEGY` | | Default `native` (trusts `X-Forwarded-For` from proxies on private networks); `none` if Quokra faces the internet directly |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | | The image logs JSON lines (`ecs`); set to empty for plain text |
| `APP_ENV` | | Set `production`: the server then refuses to start if dev agent tokens are turned on |

Never set `SPRING_PROFILES_ACTIVE` to `dev` or `demo` in production: they load public demo data and keys.

---

## 4. Run it

```bash
docker run -d --name quokra -p 8080:8080 \
  -e APP_ENV=production \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/quokra \
  -e SPRING_DATASOURCE_USERNAME=quokra -e SPRING_DATASOURCE_PASSWORD=… \
  -e PUBLIC_BASE_URL=https://chat.example.com \
  -e QUOKRA_SESSION_SIGNING_KEY=… -e TENANT_SECRET_ENCRYPTION_KEY=… \
  -e OIDC_ISSUER_URI=https://login.example.com/realms/main \
  -v /srv/quokra/bots:/bots -e QUOKRA_BOTS_DIR=/bots \
  quokra
```

- **Health:** `GET /actuator/health/liveness` and `/actuator/health/readiness` (the image has a health check).
- **Proxy:** pass WebSocket upgrades through for `/ws`, and keep `X-Forwarded-For` / `X-Forwarded-Proto`.
- **One instance for now:** rate limits and the WebSocket broker are in memory. Running several instances
  needs a shared broker and limiter (later).
- **Backups:** back up the PostgreSQL database. Keep `TENANT_SECRET_ENCRYPTION_KEY` safe alongside it.

---

## 5. First steps after it runs

1. In your identity provider, give yourself the `QUOKRA_ADMIN` role, and your support staff the
   `SUPPORT_AGENT` role. Tokens for Quokra need the `quokra-api` audience.
2. Open the console from your dashboard (see the [embedding guide](EMBEDDING.md)) and open **Settings** (the gear icon).
3. Create a company: name, colour, bot file, the websites that show the chat and the dashboards that show
   the console. Copy the publishable key and embed snippets for the site's developers.
4. Add agents by work email. They join when they first sign in.
5. If the company identifies logged-in customers, create its secret key and give it to their backend team.
