# 10. Set up AI

Quokra can use AI in two ways, both only from documents a company adds itself:

- **Suggested replies for agents:** the ✨ button in the console's reply box drafts an answer with its
  sources. The agent edits or sends it; nothing reaches the customer without them.
- **Answers to customers:** when the bot's menu and keywords don't match a typed question, the bot answers
  from the documents, marked "AI answer" with its sources and the buttons "That helps" and "Talk to a
  person". With no good answer, the chat goes the usual way: fallback, then a person.

Both are off by default for every company, since they send customers' recent messages to the AI provider.

**You need:** a key for an OpenAI-compatible API, and a server admin or company admin to change Settings.

1. **Give the server a key.** Set `QUOKRA_AI_KEY` and restart. By default Quokra uses Google's Gemini API
   (`gemini-3.1-flash-lite` for answers, `gemini-embedding-001` for search); a free key comes from Google AI
   Studio. For OpenAI or a model of your own (for example with Ollama), also set `QUOKRA_AI_BASE_URL` and
   the model names ([Deploying](../DEPLOY.md#3-settings)).
   > Free tiers may use what's sent to improve their models. Use them with test data only; for real
   > customers, use a paid tier or your own model.
2. **Add documents.** In the console: Settings → **Knowledge** → **New document**. Paste text or Markdown
   (or pick a file), up to 200 KB each. Markdown headings split it into parts and name them, so a
   question about returns is answered from "Shipping › Returns". Parts are searchable a few seconds after
   saving. Good documents are short and factual: policies, prices, steps, opening times.
3. **Turn it on.** Settings → General → **AI**: **Suggest replies to agents**, and if you want the bot to
   answer too, **Answer customers' questions the bot can't**. Save.
4. **Try it.** As a customer, type a question your documents answer but the menu doesn't. The answer
   comes a few seconds later. Then ask something off-topic ("write me a poem"): it should get the
   bot's fallback, not an AI answer.

**What keeps it safe**
- The AI is only asked when a document part is close enough to the question (`QUOKRA_AI_MIN_SCORE`), so
  off-topic messages cost nothing.
- It's told to answer only from the parts it gets. The server drops answers that don't cite them, link
  anywhere they don't, contain HTML or run long.
- Agents see every AI answer in the chat, with its sources.

**Limits** (all set on the server, [Deploying](../DEPLOY.md#3-settings)): 10 AI answers per chat, 20 per
customer IP an hour, 200 AI calls per company and 1,000 for the whole server a day. Reaching one is never
an error: customers get the usual fallback and handoff, and agents see "AI limit reached". Settings → AI
shows how many calls the company used today. `QUOKRA_AI_ENABLED=false` switches AI off for the whole
server.

**If it doesn't answer**
- Settings → AI says "This server has no AI key": set `QUOKRA_AI_KEY` and restart.
- The suggestion says "Nothing in your documents answers this": the question isn't close to any part.
  Add or reword a document; `GET /api/v1/admin/tenants/{slug}/knowledge/search?q=…` shows the closest
  parts and their scores ([API](../API.md)).
- "The AI didn't answer": the provider was slow or busy. Free tiers often are; it's retried once.
