# End-to-end tests

One browser test of the whole flow: a customer asks for a person, an agent answers from the console,
replies to a message and sends an image, the customer sees it all live and then starts a new chat.

```bash
# From the repo root: Quokra + PostgreSQL with the demo data and the demo sign-in
docker compose -f docker-compose.yml -f e2e/docker-compose.e2e.yml up -d --build --wait postgres quokra

cd e2e
pnpm install
pnpm exec playwright install chromium
pnpm test                      # E2E_BASE_URL=... to test another address
```

CI runs the same on every push and pull request (`.github/workflows/ci.yml`, job `e2e`). Each run uses a
new customer, so it can run again on the same database.
