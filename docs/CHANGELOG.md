# Changelog

What changed, newest first. Each entry says what people notice; the commits have the details.

**Other docs:** [Plan](PLAN.md) · [API](API.md) · [Guides](guides/README.md)

---

## 2.4.1 (demo documents, steadier AI), 2026-11-05

- When the AI provider is busy, Quokra tries twice more (after 1 and 3 seconds) instead of once, so free tiers
  that are briefly overloaded give fewer "Sorry, I didn't get that" (`QUOKRA_AI_RETRY_WAITS`).
- The demo company has six more documents about Quokra (adding it, the console, the bot, AI, privacy,
  licence), so its AI can answer most questions about it. The demo bot's keywords are narrower, so more
  typed questions reach the AI instead of a menu answer.

## 2.4.0 (v3 complete, chat and console improvements), 2026-11-04

**AI (v3 is complete)**
- Handoff summary: when a chat joins the queue, the AI leaves the agent a private note saying what the
  customer wants and the details they gave (with **Suggest replies to agents** on).
- New guide: [Set up AI](guides/10-set-up-ai.md).
- The demo company has a sample knowledge document, so its AI can answer once the server has a key, and the
  demo bot's "Is there AI?" answer is up to date.

**Chat and console**
- Notification sounds now play when the console or chat is embedded: `sdk.js` plays them on the host page,
  where people actually click. Customers hear a sound for a new reply while the chat is closed or the tab
  is in the background, and can turn it off in the chat's **⋯** menu.
- Web addresses in messages are clickable (with `http(s)://`, `www.` or a known ending like `.com`); emails
  stay text.
- Copy buttons work inside an embedded console, and copying a customer's email says **Copied**.
- A go-to-latest button shows while scrolled up in a chat; new messages no longer pull you down while you
  read older ones (your own still do).
- Options-only bots: with `free_text: false` in the bot file (or **Customers can type to the bot** off in
  Settings → Bot), customers pick a topic instead of typing while the bot answers.
- Customers can rate a resolved chat with a thumb up or down and an optional comment. The team sees the
  rating on the chat (under the thread, in Details, and as a badge in the list); admins get a summary in
  Settings → Ratings.
- **Email me this chat** in the chat's **⋯** menu sends customers their chat, to the email they gave in it.
  Needs email set up on the server.
  At most 3 a day go to any one address, and each says why it was sent, since anyone can type an address.
- An emoji button in the message box, with tabs and recently used ones first.
- Louder notification sounds, with a Low / Medium / High volume and a Test button in the bell menu.
- Console: keyboard shortcuts (`?` lists them), a colour per customer avatar, search matches marked in the
  list, a friendlier empty state with today's numbers, and menus with icons.
- Console: **Back to chats** in Settings, sign out asks first, and the Resolved tab counts unread messages in
  resolved chats you handled.
- Settings' sidebar has an icon per section, marks the current one, and groups your own settings apart
  from the company's.
- The server's address (`/`) opens the console instead of "Not found".
- Highlights follow the company's colour, text on it stays white unless the colour is very light, and the
  chat's robot avatar is redrawn as an outline head.
- Bot topics can have follow-up questions (`follow_ups`, buttons after the answer, then **Main topics** and
  **Talk to a person**), and `in_menu: false` keeps a topic out of the main list while its keywords still
  match. Bot messages can show links with short labels (`[label](https://…)`).
- While the bot asks for an email and name, customers can go back to the topics instead.
- The chat catches likely typos in a guest's email (`gmial.com`) and offers the fix once before sending.
- A message that didn't send says why, instead of a bare "Not sent".
- Each chat records where it came from (page path, referring site, campaign tags, country, device,
  language, time zone; no IP address), shown as **Came from** in the console. Sites can pass their own
  visitor id (`visitorId`), only with the visitor's consent.

**Admins**
- Company admins and server admins who aren't agents can read their companies' chats, notes and history,
  read-only. Acting on a chat still needs the agent role and membership.
- An agent's name is never shown as an email: a name claim like `sam.lee@…` shows as `Sam Lee`.

**Fixes**
- Tapping bot buttons has its own limit (60 a minute), so clicking through topics no longer blocks typing.
- Agent tokens with a `type` claim other than `access` (refresh tokens from some providers) are refused.
- The typing dots no longer sit in a stretched bubble; long agent names in the console header end in "…".
- The dev `compose.yaml` has its own project name (`quokra-dev`), so it no longer shares containers with
  other checkouts. Remove the old containers once (`docker compose -p server down`), since both use port
  8180; the dev database starts fresh with the dev seed.

## 2.3.0 (AI limits), 2026-11-04

- AI limits: 10 answers per chat, 20 per customer IP per hour, 200 calls per company and 1,000 per server per
  day, all set by env (`QUOKRA_AI_PER_*`, DEPLOY.md). Reaching one means the usual fallback and handoff, and
  "AI limit reached" for suggestions. Settings → AI shows today's use. `QUOKRA_AI_ENABLED=false` switches AI
  off for the whole server.

## 2.2.0 (AI suggestions and answers), 2026-11-03

**AI from the company's documents (v3)**
- Companies add documents in Settings → Knowledge (text or Markdown). They're split into parts and searched
  by meaning.
- Agents can ask for a reply drafted from them (the ✨ button in the reply box). It shows where it came
  from, and the agent edits or sends it; nothing reaches the customer without them. Turn on **Suggest
  replies to agents** in Settings → General → AI.
- The bot can answer customers itself (the second switch there): a typed question the menu didn't match
  gets an answer from the documents, marked "AI answer" with its sources, and buttons "That helps" and
  "Talk to a person". No good answer means the usual fallback and handoff. At most 10 per chat.
- Both are off by default, since they send customers' recent messages to the AI provider.
- Guardrails: the AI is only asked when the documents have a close match, answers only from them, and the
  server drops answers that don't cite them, link elsewhere or aren't plain text.
- Needs an OpenAI-compatible provider: `QUOKRA_AI_KEY` and friends (DEPLOY.md). Without a key, AI is off.

**Other changes**
- Opening hours can run past midnight (`22:00-02:00`) and close at midnight (`18:00-24:00`); `00:00-24:00`
  is all day.
- A company admin can no longer switch off their own Admin or remove themselves by mistake; another admin
  of the company (or a server admin) still can.

**Upgrading:** the database now needs the **pgvector** extension. Neon and most hosted PostgreSQL have it; with
Docker, use `pgvector/pgvector:pg17` instead of `postgres:17-alpine`. Back up first: the pgvector image is
Debian-based, so either move the data with `pg_dump` / `pg_restore`, or reindex text indexes after switching.

## 2.1.0 (company admins and review fixes), 2026-10-31

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
