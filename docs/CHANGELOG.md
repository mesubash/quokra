# Changelog

What changed, newest first. Each entry says what people notice; the commits have the details.

**Other docs:** [Plan](PLAN.md) · [API](API.md) · [Guides](guides/README.md)

---

## Unreleased

**Company admins**
- Someone can now manage one company's settings without managing the whole server: turn on **Admin** for
  them in Settings → Agents (or invite them as admin). They only see their own company and can't create
  new ones. Server admins (the admin role) still manage everything.
- Or let the identity provider decide: with `QUOKRA_COMPANY_ADMIN_CLAIM` set, a token value like
  `quokra:shop:admin` makes someone company admin of `shop`.
- The public demo's sign-in is now company admin of the demo company only.

**Fixes** (from a code and security review of 2.0)
- An agent can only reopen the customer's latest chat; reopening an older one (after "Start a new chat")
  could leave replies the customer never sees and break their next message.
- A guest asked for their email a second time (after a chat closed itself) no longer has it saved as their
  name.
- Waiting-chat emails: a busy company with an agent online can no longer hold up other companies' alerts,
  and an email that fails to send is tried again.
- Customer emails only contain replies not emailed before, and no longer include the customer's typed name.
- In the chat: a reply quote is dropped when starting a new chat, a late answer about the old chat can't
  switch back to it, Escape on a quote or an image no longer closes the whole chat, and the online / away
  header refreshes while the chat is open.

**Security**
- Image uploads are limited per IP address (20 a minute) and per chat (30), and agents' uploads per agent.
- The public demo profile never sends email.
- The chat and console send their messages to the embedding page's own origin only (`sdk.js` passes it).
- One company failing in the nightly retention run no longer stops the others.
- Docs say clearly that the admin role operates the whole server.

## 2.0.0 (v2, daily operations), 2026-10-30

### 2026-10-30

**Customers and agents**
- **Online and away.** Agents set their status from their avatar; the open console checks in every minute,
  so closing it counts as away. The chat says the team is online only while someone is, and the transfer
  list and Settings → Agents show who's online.
- **Typing indicator.** The customer sees "Sam is typing…" while the agent writes a reply, and the agent sees
  the customer typing. Nothing is stored.

**Customers**
- **Reply to a message.** Hover a message (on phones the button is always shown) and pick Reply; the answer
  quotes it, and clicking the quote jumps to the original. Agents can do the same.
- **Images.** Customers and agents can send PNG, JPEG, GIF or WebP up to 5 MB, checked by the file's first
  bytes. Click one to see it full size in the chat. Off per company until an admin allows it.
- **Start a new chat** from the ⋯ menu in the chat header. The old chat closes and stays in the team's
  history; a guest's email carries over.
- **Opening hours.** Outside them the header says the team is away and the handoff message says when it's
  back; chats still reach the queue.

**Agents**
- **Email alerts**, when the server has SMTP set up: the team is emailed about a chat that has waited too
  long with nobody online, and (if the company turns it on) customers get a reply they missed by email.
  Settings → General → Email shows whether email works.
- **Notifications:** sounds, toasts and browser notifications for new chats, messages in my chats,
  transfers and notes, with a bell menu and Settings → Notifications to choose.
- **Saved replies** picked by typing `/` in the reply box, with `{name}` filled in.
- **Quiet chats close by themselves** after a time each company sets (default 24 hours).
- **A login that keeps working:** a call refused for an expired token gets a fresh one from the dashboard
  and is retried once.

**Admins**
- **Keep chats for** a time each company chooses (default 12 months), within the server's limits
  (`QUOKRA_RETENTION_MIN_MONTHS` / `_MAX_MONTHS`). Resolved chats older than that are deleted at night,
  with their messages and images; contacts stay.
- **Settings behind the gear icon** instead of an Admin tab: General, Notifications, Bot, Saved replies,
  Agents, Embed & keys, as plain rows rather than cards. A console embedded for one company only shows that
  company; managing all companies needs a console mounted without `tenant`.
- **The bot is edited in Settings:** greeting, questions, keywords, answers, handing over, and the chat's own
  wording. No YAML or restart needed; bot files still work as the starting point.
- **Company colour everywhere:** the chat, the embedded console and its floating button. Very light colours
  stay readable.
- **Image storage** on disk or in S3-compatible storage (AWS S3, Cloudflare R2, Garage, MinIO), with a
  storage check in Settings and in `/actuator/health`.

**Docs and deployment**
- Step-by-step [guides](guides/README.md), and this changelog.
- **A free public demo** on Render, Neon and Vercel: `render.yaml`, the `public-demo` profile (demo sign-in
  for the playground's site, set with `QUOKRA_DEMO_ORIGINS`), and [a guide](guides/09-free-demo.md).
- The Docker image's memory settings can be changed with `JAVA_TOOL_OPTIONS`.

**Fixes**
- Reopening an old chat while the customer has a newer one now says so (`409 NEWER_CHAT_OPEN`) instead of
  failing.

---

## 1.0.0 (v1, FAQ bot + human handoff)

The chat panel and the agent console, both embeddable with one script tag; a FAQ bot with a menu and
keywords; handoff to the team in the same conversation; live updates; guests and identified customers;
agent login through your own OIDC provider; companies, keys and agents managed in the console; a Docker
image. See [Plan §6](PLAN.md) for the build steps.
