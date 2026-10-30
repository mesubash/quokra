# 8. Notifications

Agents get a short sound and a toast in the console, and (if allowed) a browser notification while the
dashboard is in another tab or app.

**What notifies whom**

| Event | Who |
|---|---|
| A new chat is waiting | Every agent of the company |
| A customer writes in a chat | The agent it's assigned to, unless they're looking at it |
| A chat is transferred | The agent receiving it |
| A colleague leaves a private note | The agent the chat is assigned to |

Each has its own short sound. Clicking a toast or a browser notification opens the chat. The console
button's badge counts waiting chats plus unread messages in your own chats.

**For the dashboard:** pass `notifications: true` when embedding the console
([Add the console](04-add-the-console.md)). Browsers only let the dashboard page itself show
notifications, so `sdk.js` shows them for the console. You can also listen with
`Quokra.console.on("notification", (n) => …)` to show them in your own UI.

**For agents:**
1. The first time, the console shows **Get notified of new chats · Turn on**. Click **Turn on** and
   allow notifications in the browser's prompt.
2. The **bell** in the console header, or **Settings → Notifications** (the gear icon), chooses what
   notifies you (the four events above) and how (sound, desktop notifications). If you closed the
   banner, turn them on there later. These settings are kept in your browser.
3. If you blocked notifications earlier, allow them again in the browser's site settings.

Sounds need one click on the page first (a browser rule); after that they play.

## Email

When nobody is watching the console, email fills the gap. It needs your mail server (see
[Deploying](../DEPLOY.md): `SMTP_HOST`, `QUOKRA_MAIL_FROM` and a login); without it Quokra sends nothing
and says so in Settings.

In **Settings → General → Email**:
- **Waiting alert:** after how long a waiting chat is emailed to the team when nobody is online (default 10
  minutes). Each chat is emailed once.
- **Send alerts to:** a team address (or a few); empty sends to each agent's own email.
- **Email customers:** when an agent replied and the customer hasn't answered in 5 minutes, they get the
  reply by email (only customers who left an email). Off by default.
