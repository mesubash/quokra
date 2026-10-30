# 1. Get started

Run everything on your machine with Docker and see a chat from both sides.

**You need:** Docker. That's it.

1. Clone the repo and start the stack:

   ```bash
   git clone git@github.com:mesubash/quokra.git && cd quokra
   docker compose up --build
   ```

   This starts Quokra (`localhost:8080`), PostgreSQL and Keycloak (`localhost:8180`) with a demo company,
   a demo bot and demo agents. The first build takes a few minutes.

2. **Customer side:** open `http://localhost:8080/w/demo?pk=pk_test_demo`. Ask the bot something
   ("is Quokra free?"), then choose **Talk to a person** and leave an email.

3. **Agent side:** run the [playground](https://github.com/mesubash/quokra-playground) (`pnpm install && pnpm dev`),
   open `http://localhost:5173`, choose **Support agent** and sign in as `sam` / `sam`. The chat you just
   started is in the **Queue**. Type a reply: that takes the chat and sends it.

4. Watch the customer window: the reply arrives live, and the header says who's answering.

**Demo logins** (all public, demo only): agents `sam` / `sam` (also an admin) and `alex` / `alex`;
`riley` / `riley` has no agent role and is refused. Keycloak admin: `admin` / `admin`.

**Stop it:** `docker compose down` (add `-v` to also delete the demo data).

Next: [set up your own company](02-set-up-a-company.md).
