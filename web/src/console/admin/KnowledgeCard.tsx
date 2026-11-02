import { useCallback, useEffect, useState, type ChangeEvent, type FormEvent } from "react";
import { ApiError, type AdminApi, type AdminTenant, type KnowledgeSummary } from "../../lib/api";

// The company's documents that AI answers will come from (v3). Text or Markdown, pasted or from a file;
// the server splits each one into parts for search.

const MAX_BODY = 200_000;

interface Form {
  id: string | null;
  title: string;
  body: string;
}

const EMPTY: Form = { id: null, title: "", body: "" };

export function KnowledgeCard({ api, tenant }: { api: AdminApi; tenant: AdminTenant }) {
  const [docs, setDocs] = useState<KnowledgeSummary[] | null>(null);
  const [form, setForm] = useState<Form>(EMPTY);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    api
      .knowledge(tenant.slug)
      .then((page) => setDocs(page.items))
      .catch(() => setDocs([]));
  }, [api, tenant.slug]);

  useEffect(load, [load]);

  async function edit(id: string) {
    setMessage(null);
    try {
      const doc = await api.document(tenant.slug, id);
      setForm({ id: doc.id, title: doc.title, body: doc.body });
    } catch {
      setMessage("Couldn't open that document.");
    }
  }

  // Reads a .txt or .md file in the browser; the title comes from the file name unless one is typed.
  async function pick(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file) return;
    if (file.size > MAX_BODY) {
      setMessage("That file is too big. Up to 200 KB of text.");
      return;
    }
    const body = await file.text();
    setForm((f) => ({ ...f, title: f.title || file.name.replace(/\.(md|markdown|txt)$/i, ""), body }));
  }

  async function save(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setMessage(null);
    const doc = { title: form.title.trim(), body: form.body };
    try {
      await (form.id ? api.updateDocument(tenant.slug, form.id, doc) : api.addDocument(tenant.slug, doc));
      setForm(EMPTY);
      load();
    } catch (err) {
      setMessage(
        err instanceof ApiError && err.status === 409
          ? err.message
          : "Couldn't save it. A title up to 120 characters and some text are needed.",
      );
    } finally {
      setBusy(false);
    }
  }

  async function remove(id: string) {
    await api.removeDocument(tenant.slug, id).catch(() => {});
    if (form.id === id) setForm(EMPTY);
    load();
  }

  return (
    <section className="settings-group">
      <ul className="admin-people">
        {docs === null && <li className="muted">Loading…</li>}
        {docs?.map((d) => (
          <li key={d.id}>
            <span className="admin-person">
              <strong>{d.title}</strong>
              <span className="muted">
                {Math.max(1, Math.round(d.length / 1000))}k characters, {d.chunks} {d.chunks === 1 ? "part" : "parts"}
              </span>
            </span>
            <button type="button" className="link-button" onClick={() => void edit(d.id)}>
              Edit
            </button>
            <button type="button" className="link-button" onClick={() => void remove(d.id)}>
              Remove
            </button>
          </li>
        ))}
        {docs?.length === 0 && <li className="muted">No documents yet.</li>}
      </ul>
      <form className="admin-form admin-reply-form" onSubmit={save}>
        <h4>{form.id ? "Edit document" : "New document"}</h4>
        <label>
          <span className="admin-label">Title</span>
          <input
            required
            maxLength={120}
            value={form.title}
            onChange={(e) => setForm({ ...form, title: e.target.value })}
            placeholder="Shipping and returns"
          />
        </label>
        <label>
          <span className="admin-label">Text or Markdown</span>
          <textarea
            required
            rows={10}
            maxLength={MAX_BODY}
            className="admin-reply-body"
            value={form.body}
            onChange={(e) => setForm({ ...form, body: e.target.value })}
            placeholder={"# Shipping\nWe ship within two working days…"}
          />
        </label>
        <label className="link-button">
          <input type="file" accept=".txt,.md,.markdown,text/plain,text/markdown" hidden onChange={(e) => void pick(e)} />
          Or pick a .txt or .md file
        </label>
        {message && (
          <p className="admin-help" role="status">
            {message}
          </p>
        )}
        <div className="admin-actions">
          {form.id && (
            <button type="button" className="link-button" onClick={() => setForm(EMPTY)}>
              Cancel
            </button>
          )}
          <button type="submit" className="button" disabled={busy || !form.title.trim() || !form.body.trim()}>
            {form.id ? "Save document" : "Add document"}
          </button>
        </div>
      </form>
    </section>
  );
}
