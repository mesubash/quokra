import { useCallback, useEffect, useState, type FormEvent } from "react";
import { ApiError, type AdminApi, type AdminBot, type AdminTenant, type BotFields } from "../../lib/api";
import { Row } from "./Row";

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
    return <p className="muted">{message ?? "Loading…"}</p>;
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
    <form className="settings-form" onSubmit={submit}>
      <p className="settings-source">
        {state.custom ? (
          <>
            Edited here; the bot file "{state.file}" isn't used.{" "}
            <button
              type="button"
              className="link-button"
              disabled={busy}
              onClick={() => void run(() => api.resetBot(tenant.slug), `Back to the bot file "${state.file}".`)}
            >
              Use the file again
            </button>
          </>
        ) : (
          `From the bot file "${state.file}". Saving makes this company's own copy.`
        )}
      </p>

      <section className="settings-group">
        <h4>Opening</h4>
        <Row label="Greeting" help="The bot's first message, above the questions.">
          <textarea
            rows={2}
            required
            value={bot.greeting}
            onChange={(e) => setBot({ greeting: e.target.value })}
            aria-label="Greeting"
          />
        </Row>
        <Row label="Teaser" help="A short line next to the chat button before it's opened. Optional.">
          <input
            value={bot.teaser ?? ""}
            onChange={(e) => setBot({ teaser: e.target.value || undefined })}
            aria-label="Teaser"
          />
        </Row>
      </section>

      <section className="settings-group">
        <h4>Questions</h4>
        <p className="settings-group-help">
          Each shows as a button. Typed messages match the keywords (separated by commas).
        </p>
        <ol className="bot-questions">
          {draft.menu.map((m, i) => (
            <li key={i}>
              <div className="bot-question-head">
                <input
                  className="bot-question-title"
                  value={m.title}
                  required
                  onChange={(e) => setItem(i, { title: e.target.value })}
                  placeholder="Button text, e.g. Opening hours"
                  aria-label={`Question ${i + 1}`}
                />
                <span className="bot-question-tools">
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
                </span>
              </div>
              <textarea
                rows={2}
                required
                value={m.answer}
                onChange={(e) => setItem(i, { answer: e.target.value })}
                placeholder="The answer"
                aria-label={`Question ${i + 1} answer`}
              />
              <input
                className="bot-question-keywords"
                value={m.keywords}
                onChange={(e) => setItem(i, { keywords: e.target.value })}
                placeholder="Keywords: hours, open, closing time"
                aria-label={`Question ${i + 1} keywords`}
              />
            </li>
          ))}
        </ol>
        <button
          type="button"
          className="button button-quiet bot-add"
          onClick={() => setDraft({ ...draft, menu: [...draft.menu, { id: "", title: "", keywords: "", answer: "" }] })}
        >
          Add a question
        </button>
      </section>

      <section className="settings-group">
        <h4>Handing over</h4>
        <Row label="Talk to a person" help="The button text, and words that mean the same when typed.">
          <input
            required
            value={bot.human.title}
            onChange={(e) => setBot({ human: { ...bot.human, title: e.target.value } })}
            aria-label="Talk to a person button"
          />
          <input
            value={draft.humanKeywords}
            onChange={(e) => setDraft({ ...draft, humanKeywords: e.target.value })}
            placeholder="person, agent, human"
            aria-label="Talk to a person keywords"
          />
        </Row>
        <Row label="Urgent" help="Words that go straight to the team, at the top of the queue, with this reply.">
          <input
            value={draft.urgentKeywords}
            onChange={(e) => setDraft({ ...draft, urgentKeywords: e.target.value })}
            placeholder="outage, payment failed"
            aria-label="Urgent keywords"
          />
          <input
            required
            value={bot.urgent.message}
            onChange={(e) => setBot({ urgent: { ...bot.urgent, message: e.target.value } })}
            aria-label="Urgent reply"
          />
        </Row>
        <Row label="Didn't understand" help="The bot's reply when nothing matches.">
          <input required value={bot.fallback} onChange={(e) => setBot({ fallback: e.target.value })} aria-label="Fallback" />
        </Row>
        <Row label="Hand over after" help="Misses in a row before the chat goes to the team.">
          <span className="admin-inline">
            <input
              type="number"
              min={1}
              max={10}
              required
              value={bot.max_fallbacks}
              onChange={(e) => setBot({ max_fallbacks: Number(e.target.value) })}
              aria-label="Misses before handing over"
            />
            <span className="muted">misses</span>
          </span>
        </Row>
        <Row label="Handoff message" help="Shown when the chat joins the queue.">
          <input
            required
            value={bot.handoff_message}
            onChange={(e) => setBot({ handoff_message: e.target.value })}
            aria-label="Handoff message"
          />
        </Row>
      </section>

      <div className="settings-save">
        {message && (
          <span className="muted" role="status">
            {message}
          </span>
        )}
        <button type="submit" className="button" disabled={busy}>
          Save bot
        </button>
      </div>
    </form>
  );
}
