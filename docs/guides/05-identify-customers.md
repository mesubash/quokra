# 5. Identify logged-in customers

Without this, everyone who chats is a guest, asked for an email before reaching your team. If your
site has its own login, tell Quokra who the customer is: agents see their name and email, the customer
keeps one conversation across devices, and they skip the email question.

**You need:** the company's **secret key** ([Set up a company](02-set-up-a-company.md), step 5). Keep it
on your server only.

1. **On your backend**, add an endpoint that signs a short identity token for the logged-in user
   (HS256, at most 5 minutes, `sub` required):

   ```js
   // Node, with jose
   import { SignJWT } from "jose";

   app.post("/api/support-identity", requireLogin, async (req, res) => {
     const token = await new SignJWT({ name: req.user.name, email: req.user.email })
       .setProtectedHeader({ alg: "HS256" })
       .setSubject(req.user.id)
       .setIssuedAt()
       .setExpirationTime("5m")
       .sign(new TextEncoder().encode(process.env.QUOKRA_SECRET_KEY));
     res.json({ token });
   });
   ```

   For Java, see the [embedding guide §4.1](../EMBEDDING.md#41-backend-java-spring-jjwt).

2. **In the page**, after the user logs in (or on load if they already are):

   ```js
   const { token } = await fetch("/api/support-identity", { method: "POST" }).then((r) => r.json());
   Quokra.chat.identify(token);
   Quokra.chat.on("identifyfailed", (code) => console.warn("Support chat:", code));
   ```

3. **On logout:** `Quokra.chat.logout()`, so the next person on the same browser starts as a guest.

A chat started as a guest before logging in is kept and linked to the customer.

**Check it:** open the chat after identifying, ask for a person, and look at the agent console: the
chat shows the customer's name and email, and no email question was asked.
