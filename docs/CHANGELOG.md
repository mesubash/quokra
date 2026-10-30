# Changelog

What changed, newest first. Each entry says what people notice; the commits have the details.

**Other docs:** [Plan](PLAN.md) · [API](API.md) · [Guides](guides/README.md)

---

## v2, daily operations (in progress)

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
- **Notifications:** sounds, toasts and browser notifications for new chats, messages in my chats,
  transfers and notes, with a bell menu and Settings → Notifications to choose.
- **Saved replies** picked by typing `/` in the reply box, with `{name}` filled in.
- **Quiet chats close by themselves** after a time each company sets (default 24 hours).
- **A login that keeps working:** a call refused for an expired token gets a fresh one from the dashboard
  and is retried once.

**Admins**
- **Settings behind the gear icon** instead of an Admin tab: General, Notifications, Bot, Saved replies,
  Agents, Embed & keys, as plain rows rather than cards. A console embedded for one company only shows that
  company; managing all companies needs a console mounted without `tenant`.
- **The bot is edited in Settings:** greeting, questions, keywords, answers, handing over, and the chat's own
  wording. No YAML or restart needed; bot files still work as the starting point.
- **Company colour everywhere:** the chat, the embedded console and its floating button. Very light colours
  stay readable.
- **Image storage** on disk or in S3-compatible storage (AWS S3, Cloudflare R2, Garage, MinIO), with a
  storage check in Settings and in `/actuator/health`.

**Docs**
- Step-by-step [guides](guides/README.md), and this changelog.

---

## v1, FAQ bot + human handoff

The chat panel and the agent console, both embeddable with one script tag; a FAQ bot with a menu and
keywords; handoff to the team in the same conversation; live updates; guests and identified customers;
agent login through your own OIDC provider; companies, keys and agents managed in the console; a Docker
image. See [Plan §6](PLAN.md) for the build steps.
