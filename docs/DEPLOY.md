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

For a free public demo instead (no real customers, no login provider), see
[A free public demo](guides/09-free-demo.md).

---

## 2. What you need

| | |
|---|---|
| Image | `ghcr.io/mesubash/quokra:<version>` (published for each release), or `docker build -t quokra .` from the repo root |
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
| `SMTP_HOST` / `SMTP_PORT` | for email | Your mail server or provider (e.g. port 587 with STARTTLS). Without `SMTP_HOST` and `QUOKRA_MAIL_FROM`, email is simply off |
| `SMTP_USERNAME` / `SMTP_PASSWORD` | for email | Its login, if it needs one. `SMTP_STARTTLS=false` for a server without STARTTLS |
| `QUOKRA_MAIL_FROM` | for email | The sender, e.g. `Support <support@example.com>`; use a domain your provider is allowed to send for |
| `QUOKRA_RETENTION_MIN_MONTHS` / `_MAX_MONTHS` | | What companies may choose for keeping resolved chats (default 1 to 60 months; max `0` also allows "forever"). A company's choice outside new limits is brought inside them |
| `QUOKRA_RETENTION_CRON` | | When old chats are deleted, server time (default `0 0 3 * * *`, 03:00 every night) |
| `QUOKRA_ATTACHMENTS_STORE` | | Where images sent in chats go: `disk` (default) or `s3` |
| `QUOKRA_ATTACHMENTS_DIR` | | For `disk`: the folder; the image uses `/data/attachments`, so mount a volume at `/data` |
| `QUOKRA_ATTACHMENTS_S3_BUCKET` / `_REGION` | for `s3` | The bucket and its region |
| `QUOKRA_ATTACHMENTS_S3_ENDPOINT` | | Only for S3-compatible stores (Cloudflare R2, Garage, MinIO), e.g. `https://<account>.r2.cloudflarestorage.com` |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | for `s3` | The bucket's key (or the server's own role on AWS) |
| `FORWARD_HEADERS_STRATEGY` | | Default `native` (trusts `X-Forwarded-For` from proxies on private networks); `none` if Quokra faces the internet directly |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | | The image logs JSON lines (`ecs`); set to empty for plain text |
| `JAVA_TOOL_OPTIONS` | | JVM settings; the image uses `-XX:MaxRAMPercentage=75`. On small hosts (512 MB) use the values in `render.yaml` |
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
  ghcr.io/mesubash/quokra:2.0.0
```

- **Health:** `GET /actuator/health/liveness` and `/actuator/health/readiness` (the image has a health check).
- **Proxy:** pass WebSocket upgrades through for `/ws`, and keep `X-Forwarded-For` / `X-Forwarded-Proto`.
- **One instance for now:** rate limits and the WebSocket broker are in memory. Running several instances
  needs a shared broker and limiter (later).
- **Email:** each company sets its alerts in Settings → General → Email, which also says whether email works. `/actuator/health` includes `email`: up when it's not configured or working, down when configured but the server doesn't answer.
- **Images:** off for each company until an admin allows them (Settings → General). `/actuator/health` includes `storage`, which is down when the store can't be written or read; the image button then stays hidden.
- **Backups:** back up the PostgreSQL database, and the images (the `/data` volume or the bucket). Keep `TENANT_SECRET_ENCRYPTION_KEY` safe alongside it.

---

## 5. First steps after it runs

1. In your identity provider, give yourself the `QUOKRA_ADMIN` role, and your support staff the
   `SUPPORT_AGENT` role. Tokens for Quokra need the `quokra-api` audience.
2. Open the console from your dashboard (see the [embedding guide](EMBEDDING.md)) and open **Settings** (the gear icon).
3. Create a company: name, colour, bot file, the websites that show the chat and the dashboards that show
   the console. Copy the publishable key and embed snippets for the site's developers.
4. Add agents by work email. They join when they first sign in.
5. If the company identifies logged-in customers, create its secret key and give it to their backend team.
