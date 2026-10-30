import { useCallback, useEffect, useState, type FormEvent } from "react";
import { ApiError, type AdminApi, type AdminBot, type AdminTenant, type BotFields } from "../../lib/api";

// The bot's questions and answers, edited here instead of in a bot file. Keywords are typed as
// comma-separated text and only split when saving, so typing a comma doesn't get eaten.

interface Item {
  id: string;
  title: string;
  keywords: string;
  answer: string;
}

interface Draft {
  bot: BotFields;
  menu: Item[];
  humanKeywords: string;
  urgentKeywords: string;
}

const words = (text: string) =>
  text
    .split(",")
    .map((w) => w.trim())
    .filter(Boolean);

function toDraft(bot: BotFields): Draft {
  return {
    bot,
    menu: bot.menu.map((m) => ({ ...m, keywords: m.keywords.join(", ") })),
    humanKeywords: bot.human.keywords.join(", "),
    urgentKeywords: bot.urgent.keywords.join(", "),
  };
}

// New questions get an id from their title; ids only need to be unique within the bot.
export function fromDraft(d: Draft): BotFields {
  const used = new Set(d.menu.map((m) => m.id).filter(Boolean));
  used.add("human");
  const menu = d.menu.map((m, i) => {
    let id = m.id;
    if (!id) {
      const base = m.title.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || `question-${i + 1}`;
      id = base;
      for (let n = 2; used.has(id); n++) id = `${base}-${n}`;
      used.add(id);
    }
    return { id, title: m.title, keywords: words(m.keywords), answer: m.answer };
  });
  return {
    ...d.bot,
    menu,
    human: { ...d.bot.human, keywords: words(d.humanKeywords) },
    urgent: { ...d.bot.urgent, keywords: words(d.urgentKeywords) },
  };
}

export function BotCard({ api, tenant }: { api: AdminApi; tenant: AdminTenant }) {
  const [state, setState] = useState<AdminBot | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const show = useCallback((b: AdminBot) => {
    setState(b);
    setDraft(b.bot ? toDraft(b.bot) : null);
  }, []);

  useEffect(() => {
    api
      .bot(tenant.slug)
      .then(show)
      .catch(() => setMessage("Couldn't load the bot."));
  }, [api, tenant.slug, show]);

  async function run(action: () => Promise<AdminBot>, done: string) {
    setBusy(true);
    setMessage(null);
    try {
      show(await action());
      setMessage(done);
    } catch (err) {
      setMessage(err instanceof ApiError ? err.message.replace(/^bot: /, "") : "Couldn't save the bot.");
    } finally {
      setBusy(false);
    }
  }

  if (!state || !draft) {
    return (
      <section className="admin-card">
        <h3>Bot</h3>
        <p className="muted admin-help">{message ?? "Loading…"}</p>
      </section>
    );
  }

  const bot = draft.bot;
  const setBot = (patch: Partial<BotFields>) => setDraft({ ...draft, bot: { ...bot, ...patch } });
  const setItem = (i: number, patch: Partial<Item>) =>
    setDraft({ ...draft, menu: draft.menu.map((m, j) => (j === i ? { ...m, ...patch } : m)) });
  const move = (i: number, by: number) => {
    const menu = [...draft.menu];
    [menu[i], menu[i + by]] = [menu[i + by], menu[i]];
    setDraft({ ...draft, menu });
  };

  function submit(e: FormEvent) {
    e.preventDefault();
    if (!draft) return;
    void run(() => api.saveBot(tenant.slug, fromDraft(draft)), "Saved. New messages use it straight away.");
  }

  return (
    <section className="admin-card">
      <h3>Bot</h3>
      <p className="muted admin-help">
        {state.custom
          ? "Edited here. The bot file below is no longer used."
          : `From the bot file "${state.file}". Saving here makes a copy for this company.`}
      </p>
      <form className="admin-form" onSubmit={submit}>
        <label>
          <span className="admin-label">Greeting</span>
          <textarea
            className="admin-reply-body"
            rows={2}
            required
            value={bot.greeting}
            onChange={(e) => setBot({ greeting: e.target.value })}
          />
        </label>
        <label>
          <span className="admin-label">Teaser (optional)</span>
          <input value={bot.teaser ?? ""} onChange={(e) => setBot({ teaser: e.target.value || undefined })} />
          <span className="admin-help muted">Shown next to the chat button before the chat is opened.</span>
        </label>

        <span className="admin-label">Questions</span>
        {draft.menu.map((m, i) => (
          <fieldset className="admin-question" key={i}>
            <div className="admin-question-head">
              <input
                value={m.title}
                required
                onChange={(e) => setItem(i, { title: e.target.value })}
                placeholder="Button title, e.g. Opening hours"
                aria-label={`Question ${i + 1} title`}
              />
              <button type="button" className="link-button" disabled={i === 0} onClick={() => move(i, -1)}>
                Up
              </button>
              <button
                type="button"
                className="link-button"
                disabled={i === draft.menu.length - 1}
                onClick={() => move(i, 1)}
              >
                Down
              </button>
              <button
                type="button"
                className="link-button"
                onClick={() => setDraft({ ...draft, menu: draft.menu.filter((_, j) => j !== i) })}
              >
                Remove
              </button>
            </div>
            <input
              value={m.keywords}
              onChange={(e) => setItem(i, { keywords: e.target.value })}
              placeholder="Keywords, separated by commas"
              aria-label={`Question ${i + 1} keywords`}
            />
            <textarea
              className="admin-reply-body"
              rows={2}
              required
              value={m.answer}
              onChange={(e) => setItem(i, { answer: e.target.value })}
              placeholder="The answer"
              aria-label={`Question ${i + 1} answer`}
            />
          </fieldset>
        ))}
        <button
          type="button"
          className="link-button admin-add"
          onClick={() => setDraft({ ...draft, menu: [...draft.menu, { id: "", title: "", keywords: "", answer: "" }] })}
        >
          Add a question
        </button>

        <div className="admin-row">
          <label>
            <span className="admin-label">"Talk to a person" button</span>
            <input
              required
              value={bot.human.title}
              onChange={(e) => setBot({ human: { ...bot.human, title: e.target.value } })}
            />
          </label>
          <label>
            <span className="admin-label">Its keywords</span>
            <input value={draft.humanKeywords} onChange={(e) => setDraft({ ...draft, humanKeywords: e.target.value })} />
          </label>
        </div>
        <label>
          <span className="admin-label">Urgent keywords</span>
          <input value={draft.urgentKeywords} onChange={(e) => setDraft({ ...draft, urgentKeywords: e.target.value })} />
          <span className="admin-help muted">These go straight to the team, at the top of the queue.</span>
        </label>
        <label>
          <span className="admin-label">Urgent reply</span>
          <input
            required
            value={bot.urgent.message}
            onChange={(e) => setBot({ urgent: { ...bot.urgent, message: e.target.value } })}
          />
        </label>
        <label>
          <span className="admin-label">When the bot doesn't understand</span>
          <input required value={bot.fallback} onChange={(e) => setBot({ fallback: e.target.value })} />
        </label>
        <div className="admin-row">
          <label>
            <span className="admin-label">Hand over after this many misses</span>
            <input
              type="number"
              min={1}
              max={10}
              required
              value={bot.max_fallbacks}
              onChange={(e) => setBot({ max_fallbacks: Number(e.target.value) })}
            />
          </label>
          <label>
            <span className="admin-label">Handoff message</span>
            <input
              required
              value={bot.handoff_message}
              onChange={(e) => setBot({ handoff_message: e.target.value })}
            />
          </label>
        </div>

        {message && (
          <p className="admin-help" role="status">
            {message}
          </p>
        )}
        <div className="admin-actions">
          {state.custom && (
            <button
              type="button"
              className="link-button"
              disabled={busy}
              onClick={() => void run(() => api.resetBot(tenant.slug), `Back to the bot file "${state.file}".`)}
            >
              Go back to the bot file
            </button>
          )}
          <button type="submit" className="button" disabled={busy}>
            Save bot
          </button>
        </div>
      </form>
    </section>
  );
}
