# 3. Add the chat to a website

**You need:** the company's slug and publishable key (from [Set up a company](02-set-up-a-company.md)), and
your site's address in its list of websites.

## The quick way: a chat bubble

Add this before `</body>` on every page that should have the chat:

```html
<script src="https://chat.example.com/sdk.js"
        data-quokra-chat="your-slug"
        data-quokra-pk="pk_live_…"
        async></script>
```

A chat button appears in the bottom-right corner once the chat has loaded. If Quokra can't be reached,
nothing is shown.

Customers come back to the same conversation on their next visit. The **⋯** menu in the chat header has
**Start a new chat** for a fresh start; the old chat stays in your team's history. Customers can reply to a
particular message (↩) and, if the company allows images, send screenshots and photos.

## Other placements

- **Inline**, inside your page: `<div data-quokra-chat-inline="your-slug" data-quokra-pk="pk_live_…"></div>`
  (520 px tall; change it with CSS on `.quokra-inline`)
- **Popup from your own button:** `<button data-quokra-chat-trigger="your-slug" data-quokra-pk="pk_live_…">Chat with us</button>`
- **Left side:** add `data-quokra-position="left"`.

## Match your site

- **Light and dark:** by default the chat follows your page's own dark mode. Force one with
  `data-quokra-theme="light"` (or `dark`, `auto`).
- **Colour:** `data-quokra-accent="#0f766e"` uses your brand colour instead of the company's.
- **Greeting card:** shown next to the button after 5 seconds; `data-quokra-teaser-delay="off"` hides it.

## From JavaScript

```js
Quokra.chat.init({ slug: "your-slug", pk: "pk_live_…", theme: "host" });
document.querySelector("#help").addEventListener("click", () => Quokra.chat.open());
Quokra.chat.on("unread", (n) => { /* show n in your own UI */ });
Quokra.chat.on("unavailable", () => { /* Quokra didn't load: show your own contact options */ });
```

## Checklist

- [ ] The script is on every page where the chat should appear.
- [ ] Your site's exact address (`https://www.…`) is in the company's websites.
- [ ] With a Content Security Policy, allow the chat domain in `script-src`, `frame-src` and `img-src`.
- [ ] If customers sign in on your site: [identify them](05-identify-customers.md), and call
      `Quokra.chat.logout()` when they log out.

Reference: [Embedding guide §2–3](../EMBEDDING.md), [sdk.js API](../API.md#9-sdkjs-javascript-api).
