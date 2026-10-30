# 7. Write the bot

Each company's bot has a greeting, a menu of common questions, keywords for typed questions, and rules
for when to hand over to a person.

**The quick way:** in the console's **Admin** tab, open the company and edit its **Bot** card: questions,
keywords and answers, reordered with Up and Down. Saving makes the company's own copy and applies to new
messages straight away; **Go back to the bot file** drops the copy. Wording in `texts` (below) stays as in
the file it was copied from.

**With a file** (handy for keeping bots in version control):

1. Copy `server/src/main/resources/bots/demo.yml` to a folder of bot files, e.g. `bots/acme.yml`, and
   point the server at it with `QUOKRA_BOTS_DIR=/path/to/bots` (restart to reload).
2. Edit it:

   ```yaml
   greeting: "Hi! How can we help?"
   teaser: "Questions? We're here."            # optional card next to the chat button

   menu:
     - id: delivery
       title: "Where's my order?"
       keywords: [order, delivery, shipping, track]
       answer: "You can track your order from the link in your confirmation email."

   human:
     title: "Talk to a person"
     keywords: [human, person, agent, someone]

   urgent:                                       # checked first; goes straight to the team, marked urgent
     keywords: [outage, payment failed]
     message: "Sorry about this. Connecting you to the team right away."

   fallback: "Sorry, I didn't get that. Pick a topic below, or choose 'Talk to a person'."
   max_fallbacks: 2                              # hand over after this many misses in a row
   handoff_message: "Connecting you with the team. Someone will reply shortly."

   texts:                                        # optional: the chat's own wording
     online: "Online · we usually reply within minutes"
     auto_closed: "We've closed this chat since we haven't heard back. Write again any time."
     offline_handoff: "We're away right now and back {opens}. Leave your message here and we'll reply then."
   ```

3. In the **Admin** tab, pick the file for the company (**Bot file**), and set **Close quiet chats**.

**Tips**
- Keywords match whole words and phrases, ignoring case and accents; the menu item with most matches wins.
- Avoid broad keywords ("about", your product name) that would match many questions.
- A mistake in the file is reported at startup with the file and field name.

All `texts` keys (header lines, input placeholder, the email/name questions, the closing message, the
away messages used outside [opening hours](02-set-up-a-company.md)) are
listed in [Architecture §6](../ARCHITECTURE.md).
