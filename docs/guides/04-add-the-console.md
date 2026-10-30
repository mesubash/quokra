# 4. Add the console to a dashboard

Agents answer from a console inside your own dashboard, signed in with your dashboard's login.

**You need:** your dashboard's address in the company's list of dashboards, agents with the
`SUPPORT_AGENT` role, and a way to get the signed-in agent's access token
([connect your identity provider](06-connect-your-login.md)).

## A full Support page

```html
<div id="support" style="height: calc(100vh - 64px)"></div>
<script src="https://chat.example.com/sdk.js"></script>
<script>
  Quokra.console.mount("#support", {
    tenant: "your-slug",
    getToken: () => auth.getAccessToken(), // the signed-in agent's token, refreshed by your login library
    notifications: true,                   // sounds, toasts and browser notifications
  });
  Quokra.console.on("unread", (n) => setNavBadge("support", n));
</script>
```

## A button on every page

For people who answer chats now and then: a floating button that opens the console in a side drawer.
With `fullView`, the drawer gets an "Open full view" button that goes to your Support page, on the same chat.

```js
Quokra.console.init({
  mode: "bubble",
  tenant: "your-slug",
  getToken: () => auth.getAccessToken(),
  notifications: true,
  fullView: (id) => router.push(id ? `/support?conversation=${id}` : "/support"),
});

// On /support, open the chat the agent came from:
Quokra.console.mount("#support", { tenant: "your-slug", getToken, conversation: params.get("conversation") });
```

Use one of the two per page: the button everywhere, and the full console on `/support`.

## When things go wrong

- **Quokra is down:** nothing broken is shown; `Quokra.console.on("unavailable", …)` fires after 15
  seconds so you can show your own message.
- **The login expired:** `getToken` should refresh it, or send the agent to sign in again. If no token
  arrives, the console says "Couldn't sign you in" with Try again.

The console follows your dashboard's light or dark mode by itself. Agents who also have the
`QUOKRA_ADMIN` role see the **Admin** tab.

Reference: [Embedding guide §5](../EMBEDDING.md#5-agent-console-in-your-dashboard), [sdk.js API](../API.md#92-agent-console-windowquokraconsole).
