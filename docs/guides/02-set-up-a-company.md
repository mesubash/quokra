# 2. Set up a company

Each website or product that uses Quokra is a **company** (a tenant) with its own keys, sites, bot and
agents. Admins set them up in the console's **Admin** tab.

**You need:** the `QUOKRA_ADMIN` role in your identity provider (in the demo, `sam` has it).

1. Open the agent console and choose **Admin**, then **New company**.
2. Fill in:
   - **Slug:** a short id like `acme-shop`. It's used in the embed code and can't change later.
   - **Name:** shown in the chat header.
   - **Brand colour** and **logo** (optional).
   - **Bot file:** which FAQ the bot answers from (see [Write the bot](07-write-the-bot.md)).
   - **Close quiet chats:** when chats without replies close by themselves (default after 24 hours).
   - **Websites that show the chat:** e.g. `https://www.acme.com`, one per line.
   - **Dashboards that show the agent console:** e.g. `https://admin.acme.com`.
3. **Create company.** You now get:
   - the **publishable key** (`pk_live_…`), safe to put in web pages;
   - ready-to-paste **embed code** for the chat and the console. Hand these to the site's developers.
4. **Agents:** add your support staff by work email. Anyone who has signed in before is added at once;
   others are invited and join on their first sign-in with that email (they need the `SUPPORT_AGENT`
   role in your identity provider).
5. **Secret key** (only if you'll [identify logged-in customers](05-identify-customers.md)): **Create
   secret key**, copy it straight away (it's shown once) and give it to the backend team. Replacing it
   stops the old one at once.
6. **Saved replies** (optional): answers your agents send often. Agents type `/` in the reply box to
   pick one; `{name}` becomes the customer's name (or "there" for guests who didn't give one).

Browsers refuse to show the chat or console on sites that aren't listed in step 2, so add every domain
(including `www.` and staging sites) you'll use.

Next: [add the chat to the website](03-add-the-chat.md).
