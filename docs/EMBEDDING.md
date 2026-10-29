# Embedding Guide

How a host adds the customer chat to a website or app, and the agent console to a dashboard. This guide is for the developer doing the integration.

**Other docs:** [Plan](PLAN.md) · [Architecture](ARCHITECTURE.md) · [API](API.md) · [Development](DEVELOPMENT.md)

> v1 is under construction; examples use `chat.example.com`. Replace with the real domain.

---

## 1. What you need

| Value | Example | Where it goes | Secret? |
|---|---|---|---|
| **Slug** | `demo` | Embed code | No |
| **Publishable key** | `pk_live_…` | Embed code | No, safe in HTML |
| **Secret key** | `sk_live_…` | Your **backend** only, to sign identity tokens | **Yes** |
| **Allowed origins** | `https://www.example.com` | Registered with your tenant | — |

Your site's origin must be registered as a **widget origin** (customer chat) and/or a **console origin** (agent console). Embedding from any other origin is blocked by the browser.

---

## 2. Customer chat: quickstart

Add one line before `</body>`:

```html
<script src="https://chat.example.com/sdk.js"
        data-quokra-chat="demo"
        data-quokra-pk="pk_live_XXXX"
        async></script>
```

A chat bubble appears in the bottom-right corner. Branding (logo, colour, name) comes from your tenant settings; no code change is needed when it changes.

---

## 3. Customer chat: other modes

### 3.1 Inline (inside your page)

```html
<div data-quokra-chat-inline="demo" data-quokra-pk="pk_live_XXXX" style="max-width: 420px"></div>
<script src="https://chat.example.com/sdk.js" async></script>
```

The frame is 520 px tall by default; set a `height` on the container's iframe via CSS (`.quokra-inline`) to change it. The panel scrolls inside.

### 3.2 Popup from your own button

```html
<button data-quokra-chat-trigger="demo" data-quokra-pk="pk_live_XXXX">Chat with us</button>
<script src="https://chat.example.com/sdk.js" async></script>
```

### 3.3 JavaScript control

```html
<script src="https://chat.example.com/sdk.js"></script>
<script>
  Quokra.chat.init({ slug: "demo", pk: "pk_live_XXXX" });
  document.querySelector("#help").addEventListener("click", () => Quokra.chat.open());
  Quokra.chat.on("unread", (n) => { document.title = n ? `(${n}) My Site` : "My Site"; });
</script>
```

### 3.4 React / Next.js

```tsx
import Script from "next/script";

export function QuokraChatLoader() {
  return (
    <Script
      src="https://chat.example.com/sdk.js"
      data-quokra-chat="demo"
      data-quokra-pk="pk_live_XXXX"
      strategy="afterInteractive"
    />
  );
}
```

---

## 4. Identify logged-in customers (optional)

Without this step every visitor is a **guest**. To show agents who the customer is, and to keep one conversation across devices, sign an identity token **on your backend** and pass it to the chat.

**Rules:** sign with your **secret key**, algorithm HS256, lifetime of **5 minutes or less**, claim `sub` required. Never put the secret key in browser code.

### 4.1 Backend: Java (Spring, jjwt)

```java
String identityToken = Jwts.builder()
    .subject(user.getId())
    .claim("name", user.getFullName())
    .claim("email", user.getEmail())
    .issuedAt(new Date())
    .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
    .signWith(Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8)))
    .compact();
```

### 4.2 Backend: Node (jose)

```js
import { SignJWT } from "jose";

const identityToken = await new SignJWT({ name: user.name, email: user.email })
  .setProtectedHeader({ alg: "HS256" })
  .setSubject(user.id)
  .setIssuedAt()
  .setExpirationTime("5m")
  .sign(new TextEncoder().encode(process.env.QUOKRA_SECRET_KEY));
```

### 4.3 Frontend

```js
const { token } = await fetch("/api/support-identity").then((r) => r.json()); // your endpoint
Quokra.chat.identify(token);
```

Call `identify` after login, or on page load for users who are already logged in. A guest conversation started before login is kept and linked to the user.

---

## 5. Agent console in your dashboard

The console runs in an iframe and uses **your dashboard's login**. Your dashboard gives it an agent access token on request.

### 5.1 Requirements

- Agents log in to your dashboard through the OIDC provider the support server trusts.
- Their access token includes the audience `quokra-api` and the role `SUPPORT_AGENT`. Where the role sits in the token is configurable (`QUOKRA_AGENT_ROLES_CLAIM`, e.g. `roles` or `realm_access.roles`).
- Each agent is a member of the tenant. In v1 the support admin adds them with SQL, using the agent's `sub` from the identity provider:

  ```sql
  insert into tenant_agents (tenant_id, agent_sub)
  select id, 'the-agent-sub' from tenants where slug = 'your-slug';
  ```
- Your dashboard's origin is registered as a **console origin**.

### 5.2 Code

```html
<div id="support-console" style="height: calc(100vh - 64px)"></div>
<script src="https://chat.example.com/sdk.js"></script>
<script>
  Quokra.console.mount("#support-console", {
    tenant: "demo",
    getToken: () => auth.getAccessToken(),   // must return a fresh token (Promise<string>)
  });
  Quokra.console.on("unread", (n) => setNavBadge("support", n));
</script>
```

React:

```tsx
import { useEffect, useRef } from "react";

export function SupportPage() {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const load = () => window.Quokra.console.mount(ref.current!, { tenant: "demo", getToken: auth.getAccessToken });
    if (window.Quokra) load();
    else {
      const s = Object.assign(document.createElement("script"), { src: "https://chat.example.com/sdk.js" });
      s.onload = load;
      document.body.appendChild(s);
    }
    return () => window.Quokra?.console.unmount();
  }, []);
  return <div ref={ref} style={{ height: "calc(100vh - 64px)" }} />;
}
```

`getToken` is called when the console starts and whenever its token expires. Return your current access token, refreshing it first if needed.

### 5.3 Bubble mode (on every dashboard page)

For staff who answer chats now and then, show a floating button with the number of waiting chats. Clicking it opens the console in a side drawer.

```html
<script src="https://chat.example.com/sdk.js"></script>
<script>
  Quokra.console.init({ mode: "bubble", tenant: "demo", getToken: () => auth.getAccessToken() });
</script>
```

Put it in your dashboard's main layout so it appears on every page. Full-page and bubble modes can be used together (e.g. bubble everywhere, full page on `/support`).

---

## 6. Mobile apps

| Approach | How |
|---|---|
| **WebView** (quick) | Open `https://chat.example.com/w/demo?pk=pk_live_XXXX&mode=full`. To identify, post `{ source: "quokra", type: "chat.identify", token }` into the page through the WebView's JavaScript bridge. Don't put tokens in the URL; URLs end up in logs |
| **Native screen** (later) | Build your own UI against the widget API ([API.md](API.md#3-widget-endpoints)) |

---

## 7. Troubleshooting

| Symptom | Likely cause |
|---|---|
| Blank frame, console error "refused to frame" | Your origin isn't in the tenant's allowed origins |
| Bubble never appears | Wrong `pk`, or the script is blocked by your Content Security Policy (allow `chat.example.com` in `script-src` and `frame-src`) |
| `identify` has no effect | Token signed with the wrong key, expired, or `sub` missing |
| Console stuck on "Waiting for sign-in" | `getToken` didn't return a token, or the token lacks `quokra-api` / `SUPPORT_AGENT` |
| Agent sees no conversations | Agent isn't a member of the tenant |

### Your CSP

If your site uses a Content Security Policy, add:

```text
script-src https://chat.example.com;
frame-src  https://chat.example.com;
```
