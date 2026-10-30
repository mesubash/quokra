# 9. A free public demo

Put Quokra and the playground online for free, to show it or to try a real deployment before your own.
It runs on free plans: **Render** for Quokra, **Neon** for the database, **Vercel** for the playground. No
credit card is needed at the time of writing; check each provider's current free plan when you sign up.

**This is a demo, not a place for real customers.** Anyone who opens it can sign in as a demo agent and
change the demo company's settings. There's no identity provider: the playground uses Quokra's demo
sign-in (the `public-demo` profile).

What to expect from free plans: Quokra sleeps after about 15 minutes without visitors, and starting it on
the free CPU takes a few minutes. To keep it awake, have an uptime monitor (e.g. UptimeRobot) open
`https://<your-quokra-address>/actuator/health/liveness` every 5 to 10 minutes; one always-on service fits
in Render's monthly free hours. Images and email are off.

## 1. The database (Neon)

1. Sign up at [neon.com](https://neon.com) and create a project (any region close to you).
2. Open **Connect** and note the host, database, user and password. Quokra needs them as:
   - `SPRING_DATASOURCE_URL` = `jdbc:postgresql://<host>/<database>?sslmode=require`
   - `SPRING_DATASOURCE_USERNAME` = the user
   - `SPRING_DATASOURCE_PASSWORD` = the password

## 2. Quokra (Render)

1. Sign up at [render.com](https://render.com) with your GitHub account, and allow it to see the Quokra
   repository.
2. **New → Blueprint**, pick the repository. Render reads `render.yaml` from the repo root.
3. Fill in what it asks for: the three `SPRING_DATASOURCE_*` values from step 1, and
   `QUOKRA_DEMO_ORIGINS` (put `https://example.com` for now; you'll set the real address in step 4).
4. **Apply**. The first build takes about 10 minutes. When it's live, note the address, e.g.
   `https://quokra-demo.onrender.com`, and open `/actuator/health`: it should say `UP`.

## 3. The playground (Vercel)

1. Sign up at [vercel.com](https://vercel.com) with GitHub and **Add New → Project** with the
   `quokra-playground` repository (it has a `vercel.json`).
2. Add these environment variables:

   | Name | Value |
   |---|---|
   | `VITE_QUOKRA_URL` | Quokra's address from step 2 |
   | `VITE_AGENT_LOGIN` | `demo` |
   | `VITE_QUOKRA_TENANT` | `demo` |
   | `VITE_QUOKRA_PK` | `pk_test_demo` |

3. **Deploy**, and note its address, e.g. `https://quokra-playground.vercel.app`.

## 4. Connect them

In Render, set `QUOKRA_DEMO_ORIGINS` to the playground's address (several: comma-separated) and save; the
service restarts. On start, Quokra adds it to the demo company's websites and dashboards and lets it use
the demo sign-in.

Open the playground: chat as a customer, and in another window sign in as Sam or Alex on the Support
page.

## If something's wrong

- **The playground can't sign in an agent, or the chat doesn't appear:** `QUOKRA_DEMO_ORIGINS` doesn't
  match the playground's address exactly (`https://`, no trailing path).
- **The first page takes long or shows an error once:** Quokra was asleep or just deployed; starting takes a
  few minutes on the free CPU. Wait and reload, and keep it awake with an uptime monitor (see above).
- **Render says it ran out of memory:** lower `-XX:MaxRAMPercentage` in `JAVA_TOOL_OPTIONS` (in
  `render.yaml` or Render's settings), e.g. to 55.
- **Everything else:** Render's **Logs** tab shows Quokra's log.

For a real deployment with your own login provider, see [Deploying](../DEPLOY.md).
