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

Optional attributes: `data-quokra-theme` is `host` (default), `light`, `dark` or `auto`; `data-quokra-accent="#0f766e"` replaces the brand colour; `data-quokra-position="left"` puts the bubble on the left; `data-quokra-teaser-delay` sets when the greeting card appears next to the bubble, in milliseconds (default `5000`, `off` to never show it). The card's text is the `teaser` in your bot file.

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

**Rules:** sign with your **secret key**, algorithm HS256, lifetime of **5 minutes or less**, claim `sub` required. Never put the secret key in browser code. The support admin creates the secret key for your tenant and sends it to you.

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

Call `identify` after login, or on page load for users who are already logged in. A guest conversation started before login is kept and linked to the user. Identified customers aren't asked for their email before reaching your team.

```js
Quokra.chat.on("identifyfailed", (code) => console.warn("Support chat identify failed:", code));
```

When the customer logs out, call `Quokra.chat.logout()`. Otherwise the next person on the same browser would see their chat.

---

## 5. Agent console in your dashboard

The console runs in an iframe and uses **your dashboard's login**. Your dashboard gives it an agent access token on request.

### 5.1 Requirements

- Agents log in to your dashboard through an OIDC provider that the Quokra server trusts (see "Connect your identity provider" below).
- Their access token includes the audience `quokra-api` and the role `SUPPORT_AGENT`. Where the role sits in the token is configurable (`QUOKRA_AGENT_ROLES_CLAIM`, e.g. `roles` or `realm_access.roles`).
- Each agent is a member of the tenant. In v1 the support admin adds them with SQL, using the agent's `sub` from the identity provider:

  ```sql
  insert into tenant_agents (tenant_id, agent_sub)
  select id, 'the-agent-sub' from tenants where slug = 'your-slug';
  ```
- Your dashboard's origin is registered as a **console origin**.

### Connect your identity provider

Quokra works with any OIDC provider (Keycloak, Auth0, Okta, Entra ID, your own). It only reads access tokens; it never sees passwords and needs no client secret. The provider must:

1. Issue **signed JWT access tokens** (RS256 or ES256), with public keys at a JWKS URL.
2. Put Quokra's **audience** in `aud` (default `quokra-api`).
3. Include `iss`, `sub` and `exp`. The `sub` is what you register as the agent's id in Quokra.
4. Put a **role** for support staff in the token (default `SUPPORT_AGENT`), in a list claim such as `roles` or `realm_access.roles`.
5. Optionally a display name in `name`, `preferred_username` or `email`.

Then set these on the Quokra server:

| Variable | Example | Notes |
|---|---|---|
| `OIDC_ISSUER_URI` | `https://login.example.com/realms/main` | Must equal the tokens' `iss` exactly |
| `OIDC_JWKS_URI` | `https://login.example.com/keys` | Only if the provider has no `/.well-known/openid-configuration` |
| `OIDC_AUDIENCE` | `quokra-api` | |
| `QUOKRA_AGENT_ROLES_CLAIM` | `roles`, `realm_access.roles` | Dot path to the list of roles |
| `QUOKRA_AGENT_ROLE` | `SUPPORT_AGENT` | |
| `QUOKRA_AGENT_NAME_CLAIM` | `full_name` | Only if the name is in an unusual claim |

Check it with a real token: `GET /api/v1/agent/me` answers `200` with the agent's name and tenants; `401` means the token didn't pass (issuer, audience, signature or expiry); `403` means the role is missing.

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

`tenant` is required: the console checks it against the tenant's console origins before it lets your page frame it.

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

The console and chat follow your page's light/dark mode by themselves (`theme: "host"`, the default), and switch when your page does. They recognise a `dark`/`light` class or a `data-theme`/`data-mode`/`data-bs-theme` attribute on `<html>` or `<body>`, the CSS `color-scheme`, and otherwise how dark your page background is. To force one, pass `theme: "light"`, `"dark"` or `"auto"`, or call `setTheme(…)`. Pass `accent: "#rrggbb"` to use your own brand colour.

`getToken` is called when the console starts and whenever its token expires. Return your current access token, refreshing it first if needed.

### 5.3 Bubble mode (on every dashboard page)

For staff who answer chats now and then, show a floating button with the number of waiting chats. Clicking it opens the console in a side drawer.

```html
<script src="https://chat.example.com/sdk.js"></script>
<script>
  Quokra.console.init({ mode: "bubble", tenant: "demo", getToken: () => auth.getAccessToken() });
</script>
```

To let agents jump from the drawer to your full-page console (like a messenger sidebar's "open in full"), pass `fullView`. A URL works anywhere; a function lets a single-page app use its own router. The open conversation comes along:

```js
Quokra.console.init({
  mode: "bubble",
  tenant: "demo",
  getToken: () => auth.getAccessToken(),
  fullView: (conversationId) => router.push(conversationId ? `/support?conversation=${conversationId}` : "/support"),
});

// On /support:
Quokra.console.mount("#support-console", { tenant: "demo", getToken, conversation: params.get("conversation") });
```

Put it in your dashboard's main layout so it appears on every page. A page holds one console, so you can use bubble mode everywhere and full-page mode on `/support`, but not both on the same page.

---

## 6. Mobile apps

| Approach | How |
|---|---|
| **WebView** (quick) | Open `https://chat.example.com/w/demo?pk=pk_live_XXXX&mode=full`. To identify, post `{ source: "quokra", type: "chat.identify", token }` into the page through the WebView's JavaScript bridge once the page has finished loading. Don't put tokens in the URL; URLs end up in logs |
| **Native screen** (later) | Build your own UI against the widget API ([API.md](API.md#3-widget-endpoints)) |

---

## 7. Troubleshooting

| Symptom | Likely cause |
|---|---|
| Blank frame, console error "refused to frame" | Your origin isn't in the tenant's allowed origins |
| Bubble never appears | Wrong `pk`, or the script is blocked by your Content Security Policy (allow `chat.example.com` in `script-src` and `frame-src`) |
| `identify` has no effect | Token signed with the wrong key, expired, or `sub` missing. Listen to `Quokra.chat.on("identifyfailed", code => …)` to see why |
| Customers see "Too many requests" | Rate limits: 5 new chat sessions a minute per IP address, 20 messages a minute per chat |
| Console stuck on "Waiting for sign-in" | `getToken` didn't return a token, or the token lacks `quokra-api` / `SUPPORT_AGENT` |
| Agent sees no conversations | Agent isn't a member of the tenant |

### Your CSP

If your site uses a Content Security Policy, add:

```text
script-src https://chat.example.com;
frame-src  https://chat.example.com;
img-src    https://chat.example.com;   # the chat button's robot image
```
