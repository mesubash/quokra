import { useCallback, useEffect, useState, type FormEvent } from "react";
import {
  ApiError,
  type AdminAgents,
  type AdminApi,
  type AdminTenant,
  type OpeningHours,
  type SavedReply,
  type StorageStatus,
  type TenantForm,
} from "../../lib/api";
import { CheckIcon, CloseIcon, CopyIcon } from "../../lib/icons";
import { initials } from "../format";
import { NotifyOptions, type NotifyOptionsProps } from "../NotifyMenu";
import { BotCard } from "./BotCard";

// Settings for admins: the companies (tenants) that use Quokra and everything about each one.
// Only shown to people with the admin role; the server checks that role on every call anyway.

export type AdminSelection = string | "new" | null;

export function useAdmin(api: AdminApi, enabled: boolean) {
  const [tenants, setTenants] = useState<AdminTenant[] | null>(null);
  const [bots, setBots] = useState<string[]>([]);
  const [selected, setSelected] = useState<AdminSelection>(null);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(() => {
    api
      .tenants()
      .then((page) => {
        setTenants(page.items);
        setError(null);
      })
      .catch(() => setError("Couldn't load the companies."));
  }, [api]);

  useEffect(() => {
    if (!enabled) return;
    reload();
    api
      .bots()
      .then((page) => setBots(page.items))
      .catch(() => {});
  }, [api, reload, enabled]);

  return { tenants, bots, selected, select: setSelected, reload, error };
}

type Admin = ReturnType<typeof useAdmin>;

type Section = "notifications" | "general" | "bot" | "replies" | "agents" | "embed";

// Notifications are each agent's own; the rest is per company, for admins.
const COMPANY_SECTIONS: { id: Section; label: string }[] = [
  { id: "general", label: "General" },
  { id: "bot", label: "Bot" },
  { id: "replies", label: "Saved replies" },
  { id: "agents", label: "Agents" },
  { id: "embed", label: "Embed & keys" },
];

// Settings, opened from the gear in the console header. Everyone gets their notifications; admins also pick
// a company and see its sections one at a time. onClose is missing for admins without the agent role, who
// only have settings.
export function Settings({
  admin,
  api,
  notifications,
  onClose,
}: {
  admin?: Admin;
  api?: AdminApi;
  notifications: NotifyOptionsProps;
  onClose?: () => void;
}) {
  const [section, setSection] = useState<Section>(admin ? "general" : "notifications");

  useEffect(() => {
    if (!onClose) return;
    const close = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    document.addEventListener("keydown", close);
    return () => document.removeEventListener("keydown", close);
  }, [onClose]);

  const tenants = admin?.tenants ?? [];
  const selected = admin?.selected ?? tenants[0]?.slug ?? null;
  const tenant = tenants.find((t) => t.slug === selected);
  const companyView = admin && api && section !== "notifications";
  const sections = [{ id: "notifications" as Section, label: "Notifications" }, ...(admin ? COMPANY_SECTIONS : [])];

  return (
    <div className="settings" role="dialog" aria-modal="true" aria-label="Settings">
      <header className="settings-header">
        <h2>Settings</h2>
        {companyView && tenants.length > 0 && (
          <label className="settings-company">
            <span className="muted">Company</span>
            <select value={selected ?? ""} onChange={(e) => admin.select(e.target.value)}>
              {selected === "new" && <option value="new">New company</option>}
              {tenants.map((t) => (
                <option key={t.slug} value={t.slug}>
                  {t.displayName}
                </option>
              ))}
            </select>
          </label>
        )}
        {companyView && (
          <button type="button" className="button button-small" onClick={() => admin.select("new")}>
            New company
          </button>
        )}
        {onClose && (
          <button type="button" className="icon-button quiet" aria-label="Close settings" onClick={onClose}>
            <CloseIcon size={18} />
          </button>
        )}
      </header>
      {companyView && admin.error && (
        <p className="console-error" role="alert">
          {admin.error}{" "}
          <button type="button" className="link-button" onClick={admin.reload}>
            Retry
          </button>
        </p>
      )}
      <div className="settings-body">
        <nav className="settings-nav" aria-label="Settings sections">
          {sections.map((s) => (
            <button
              key={s.id}
              type="button"
              aria-current={section === s.id && selected !== "new" ? "page" : undefined}
              onClick={() => {
                setSection(s.id);
                if (admin?.selected === "new") admin.select(null);
              }}
            >
              {s.label}
            </button>
          ))}
        </nav>
        <div className="settings-content" key={`${selected} ${section}`}>
          {section === "notifications" ? (
            <section className="admin-card settings-notify">
              <h3>Notifications</h3>
              <p className="muted admin-help">Just for you, in this browser.</p>
              <NotifyOptions {...notifications} />
            </section>
          ) : !companyView ? null : selected === "new" ? (
            <section className="admin-card">
              <h3>New company</h3>
              <TenantFormView
                api={api}
                bots={admin.bots}
                onSaved={(t) => {
                  admin.reload();
                  admin.select(t.slug);
                  setSection("embed");
                }}
              />
            </section>
          ) : !tenant ? (
            <p className="muted">
              {admin.tenants === null ? "Loading…" : "No companies yet. Create one to get started."}
            </p>
          ) : (
            <>
              {section === "general" && (
                <section className="admin-card">
                  <h3>General</h3>
                  <TenantFormView api={api} bots={admin.bots} tenant={tenant} onSaved={admin.reload} />
                </section>
              )}
              {section === "bot" && <BotCard api={api} tenant={tenant} />}
              {section === "replies" && <RepliesCard api={api} tenant={tenant} />}
              {section === "agents" && <AgentsCard api={api} tenant={tenant} onChanged={admin.reload} />}
              {section === "embed" && (
                <>
                  <EmbedCard tenant={tenant} />
                  <SecretCard api={api} tenant={tenant} onChanged={admin.reload} />
                </>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}

// The publishable key and ready-to-paste snippets. The key is public, so it's fine to show.
function EmbedCard({ tenant }: { tenant: AdminTenant }) {
  const origin = window.location.origin;
  const chat = `<script src="${origin}/sdk.js"\n        data-quokra-chat="${tenant.slug}"\n        data-quokra-pk="${tenant.publishableKey}"\n        async></script>`;
  const consoleSnippet = `<div id="support" style="height: 100vh"></div>\n<script src="${origin}/sdk.js"></script>\n<script>\n  Quokra.console.mount("#support", {\n    tenant: "${tenant.slug}",\n    getToken: () => auth.getAccessToken(), // your dashboard's login\n  });\n</script>`;
  return (
    <section className="admin-card">
      <h3>Embed</h3>
      <div className="admin-field">
        <span className="admin-label">Publishable key</span>
        <span className="admin-value">
          <code>{tenant.publishableKey}</code>
          <CopyButton text={tenant.publishableKey} label="Copy publishable key" />
        </span>
      </div>
      <Snippet label="Customer chat, on the company's website" code={chat} />
      <Snippet label="Agent console, in the company's dashboard" code={consoleSnippet} />
      {(tenant.widgetOrigins.length === 0 || tenant.consoleOrigins.length === 0) && (
        <p className="notice admin-notice">
          Add the company's sites under Settings, or browsers will refuse to show the chat or console there.
        </p>
      )}
    </section>
  );
}

function Snippet({ label, code }: { label: string; code: string }) {
  return (
    <div className="admin-snippet">
      <div className="admin-snippet-head">
        <span className="admin-label">{label}</span>
        <CopyButton text={code} label={`Copy: ${label}`} />
      </div>
      <pre>
        <code>{code}</code>
      </pre>
    </div>
  );
}

function SecretCard({ api, tenant, onChanged }: { api: AdminApi; tenant: AdminTenant; onChanged: () => void }) {
  const [secret, setSecret] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function rotate() {
    setConfirming(false);
    setError(null);
    try {
      setSecret((await api.rotateSecret(tenant.slug)).secretKey);
      onChanged();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Couldn't create a secret key.");
    }
  }

  return (
    <section className="admin-card">
      <h3>Secret key</h3>
      <p className="muted admin-help">
        Only needed to identify logged-in customers: the company's backend signs a short token with it. Keep it on the
        server, never in a web page.
      </p>
      {secret ? (
        <div className="admin-secret" role="status">
          <strong>Copy it now: it won't be shown again.</strong>
          <span className="admin-value">
            <code>{secret}</code>
            <CopyButton text={secret} label="Copy secret key" />
          </span>
        </div>
      ) : (
        <p className="admin-help">{tenant.hasSecret ? "A secret key is set." : "No secret key yet."}</p>
      )}
      {error && <p className="error-text">{error}</p>}
      {confirming ? (
        <div className="admin-actions">
          <span className="muted">The current key stops working at once.</span>
          <button type="button" className="button button-quiet" onClick={() => setConfirming(false)}>
            Cancel
          </button>
          <button type="button" className="button button-danger" onClick={() => void rotate()}>
            Replace key
          </button>
        </div>
      ) : (
        <div className="admin-actions">
          <button
            type="button"
            className="button button-quiet"
            onClick={() => (tenant.hasSecret ? setConfirming(true) : void rotate())}
          >
            {tenant.hasSecret ? "Replace secret key…" : "Create secret key"}
          </button>
        </div>
      )}
    </section>
  );
}

function AgentsCard({ api, tenant, onChanged }: { api: AdminApi; tenant: AdminTenant; onChanged: () => void }) {
  const [data, setData] = useState<AdminAgents | null>(null);
  const [email, setEmail] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    api
      .agents(tenant.slug)
      .then(setData)
      .catch(() => setData({ agents: [], invites: [] }));
  }, [api, tenant.slug]);

  useEffect(load, [load]);

  async function add(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setMessage(null);
    try {
      const { result } = await api.addAgent(tenant.slug, email.trim());
      setMessage(
        result === "ADDED"
          ? `${email.trim()} was added.`
          : `${email.trim()} is invited and joins when they first sign in with that email.`,
      );
      setEmail("");
      load();
      onChanged();
    } catch (err) {
      setMessage(
        err instanceof ApiError && err.status === 400
          ? "That doesn't look like an email address."
          : "Couldn't add them.",
      );
    } finally {
      setBusy(false);
    }
  }

  async function remove(action: () => Promise<void>) {
    await action().catch(() => {});
    load();
    onChanged();
  }

  return (
    <section className="admin-card">
      <h3>Agents</h3>
      <p className="muted admin-help">
        Add people by their work email. They sign in with the company's login and need the agent role there.
      </p>
      <form className="admin-inline-form" onSubmit={add}>
        <input
          type="email"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          placeholder="name@company.com"
          aria-label="Agent's email"
        />
        <button type="submit" className="button" disabled={busy || !email.trim()}>
          Add agent
        </button>
      </form>
      {message && (
        <p className="admin-help" role="status">
          {message}
        </p>
      )}
      <ul className="admin-people">
        {data === null && <li className="muted">Loading…</li>}
        {data?.agents.map((a) => (
          <li key={a.sub}>
            <span className="avatar avatar-agent">{initials(a.name)}</span>
            <span className="admin-person">
              <strong>{a.name}</strong>
              <span className="muted">{a.email ?? a.sub}</span>
            </span>
            <button
              type="button"
              className="link-button"
              onClick={() => void remove(() => api.removeAgent(tenant.slug, a.sub))}
            >
              Remove
            </button>
          </li>
        ))}
        {data?.invites.map((i) => (
          <li key={i.email}>
            <span className="avatar">?</span>
            <span className="admin-person">
              <strong>{i.email}</strong>
              <span className="badge">Invited</span>
            </span>
            <button
              type="button"
              className="link-button"
              onClick={() => void remove(() => api.removeInvite(tenant.slug, i.email))}
            >
              Cancel
            </button>
          </li>
        ))}
        {data && data.agents.length === 0 && data.invites.length === 0 && <li className="muted">No agents yet.</li>}
      </ul>
    </section>
  );
}

const NO_REPLY = { id: "", title: "", body: "" };

// Saved replies agents pick by typing "/" in the reply box.
function RepliesCard({ api, tenant }: { api: AdminApi; tenant: AdminTenant }) {
  const [replies, setReplies] = useState<SavedReply[] | null>(null);
  const [form, setForm] = useState<SavedReply>(NO_REPLY);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    api
      .replies(tenant.slug)
      .then((page) => setReplies(page.items))
      .catch(() => setReplies([]));
  }, [api, tenant.slug]);

  useEffect(load, [load]);

  async function save(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setMessage(null);
    try {
      await (form.id ? api.updateReply(tenant.slug, form) : api.addReply(tenant.slug, form));
      setForm(NO_REPLY);
      load();
    } catch {
      setMessage("Couldn't save it. A title up to 80 characters and a reply are needed.");
    } finally {
      setBusy(false);
    }
  }

  async function remove(id: string) {
    await api.removeReply(tenant.slug, id).catch(() => {});
    if (form.id === id) setForm(NO_REPLY);
    load();
  }

  return (
    <section className="admin-card">
      <h3>Saved replies</h3>
      <p className="muted admin-help">
        Agents type / in the reply box to pick one. {"{name}"} becomes the customer's name.
      </p>
      <ul className="admin-people">
        {replies === null && <li className="muted">Loading…</li>}
        {replies?.map((r) => (
          <li key={r.id}>
            <span className="admin-person">
              <strong>{r.title}</strong>
              <span className="muted">{r.body}</span>
            </span>
            <button type="button" className="link-button" onClick={() => setForm(r)}>
              Edit
            </button>
            <button type="button" className="link-button" onClick={() => void remove(r.id)}>
              Remove
            </button>
          </li>
        ))}
        {replies?.length === 0 && <li className="muted">No saved replies yet.</li>}
      </ul>
      <form className="admin-form" onSubmit={save}>
        <label>
          <span className="admin-label">Title</span>
          <input
            required
            maxLength={80}
            value={form.title}
            onChange={(e) => setForm({ ...form, title: e.target.value })}
            placeholder="Opening hours"
          />
        </label>
        <label>
          <span className="admin-label">Reply</span>
          <textarea
            required
            rows={3}
            maxLength={2000}
            className="admin-reply-body"
            value={form.body}
            onChange={(e) => setForm({ ...form, body: e.target.value })}
            placeholder="Hi {name}, we're open Monday to Friday, 9 to 5."
          />
        </label>
        {message && (
          <p className="admin-help" role="status">
            {message}
          </p>
        )}
        <div className="admin-actions">
          {form.id && (
            <button type="button" className="link-button" onClick={() => setForm(NO_REPLY)}>
              Cancel
            </button>
          )}
          <button type="submit" className="button" disabled={busy || !form.title.trim() || !form.body.trim()}>
            {form.id ? "Save reply" : "Add reply"}
          </button>
        </div>
      </form>
    </section>
  );
}

// Choices for closing quiet chats, in minutes (0 = never).
const AUTO_RESOLVE = [
  { minutes: 0, label: "Never" },
  { minutes: 60, label: "After 1 hour" },
  { minutes: 240, label: "After 4 hours" },
  { minutes: 480, label: "After 8 hours" },
  { minutes: 1440, label: "After 24 hours" },
  { minutes: 2880, label: "After 2 days" },
  { minutes: 4320, label: "After 3 days" },
  { minutes: 10080, label: "After 7 days" },
];

const lines = (text: string) =>
  text
    .split(/[\n,]+/)
    .map((s) => s.trim())
    .filter(Boolean);

function TenantFormView({
  api,
  bots,
  tenant,
  onSaved,
}: {
  api: AdminApi;
  bots: string[];
  tenant?: AdminTenant;
  onSaved: (t: AdminTenant) => void;
}) {
  const [slug, setSlug] = useState("");
  const [name, setName] = useState(tenant?.displayName ?? "");
  const [color, setColor] = useState(tenant?.primaryColor ?? "");
  const [logo, setLogo] = useState(tenant?.logoUrl ?? "");
  const [bot, setBot] = useState(tenant?.botConfig ?? "");
  const [autoResolve, setAutoResolve] = useState(tenant?.autoResolveMinutes ?? 1440);
  const [hours, setHours] = useState<OpeningHours | null>(tenant?.openingHours ?? null);
  const [images, setImages] = useState(tenant?.attachmentsEnabled ?? false);
  const [widget, setWidget] = useState((tenant?.widgetOrigins ?? []).join("\n"));
  const [consoleSites, setConsoleSites] = useState((tenant?.consoleOrigins ?? []).join("\n"));
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!bot && bots.length > 0) setBot(bots[0]);
  }, [bot, bots]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setSaved(false);
    const form: TenantForm = {
      displayName: name,
      primaryColor: color || null,
      logoUrl: logo || null,
      botConfig: bot,
      autoResolveMinutes: autoResolve,
      openingHours: hours,
      attachmentsEnabled: images,
      widgetOrigins: lines(widget),
      consoleOrigins: lines(consoleSites),
    };
    try {
      const result = tenant ? await api.update(tenant.slug, form) : await api.create({ ...form, slug });
      setSaved(true);
      onSaved(result);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Couldn't save.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="admin-form" onSubmit={submit}>
      {!tenant && (
        <label>
          <span className="admin-label">Slug</span>
          <input
            value={slug}
            onChange={(e) => setSlug(e.target.value.toLowerCase())}
            placeholder="acme-shop"
            required
            pattern="[a-z0-9][a-z0-9-]{0,38}[a-z0-9]"
          />
          <span className="admin-help muted">
            Lowercase letters, digits and dashes. Used in embed code; can't change later.
          </span>
        </label>
      )}
      <label>
        <span className="admin-label">Name</span>
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Acme" required maxLength={80} />
        <span className="admin-help muted">Shown in the chat header.</span>
      </label>
      <div className="admin-row">
        <label>
          <span className="admin-label">Brand colour</span>
          <span className="admin-color">
            <input
              type="color"
              value={/^#[0-9a-fA-F]{6}$/.test(color) ? color : "#0051fb"}
              onChange={(e) => setColor(e.target.value)}
              aria-label="Pick a colour"
            />
            <input value={color} onChange={(e) => setColor(e.target.value)} placeholder="#0051fb" maxLength={7} />
          </span>
        </label>
        <label>
          <span className="admin-label">Bot file</span>
          <select value={bot} onChange={(e) => setBot(e.target.value)} required>
            {bots.map((b) => (
              <option key={b} value={b}>
                {b}
              </option>
            ))}
          </select>
        </label>
      </div>
      <label>
        <span className="admin-label">Close quiet chats</span>
        <select value={autoResolve} onChange={(e) => setAutoResolve(Number(e.target.value))}>
          {AUTO_RESOLVE.some((o) => o.minutes === autoResolve) ? null : (
            <option value={autoResolve}>After {autoResolve} minutes</option>
          )}
          {AUTO_RESOLVE.map((o) => (
            <option key={o.minutes} value={o.minutes}>
              {o.label}
            </option>
          ))}
        </select>
        <span className="admin-help muted">
          Chats with the bot that the customer left, and chats where the agent wrote last, close by themselves after
          this long without messages. Waiting chats never do.
        </span>
      </label>
      <HoursField value={hours} onChange={setHours} />
      <ImagesField api={api} value={images} onChange={setImages} />
      <label>
        <span className="admin-label">Logo URL (optional)</span>
        <input value={logo} onChange={(e) => setLogo(e.target.value)} placeholder="https://www.acme.com/logo.png" />
      </label>
      <label>
        <span className="admin-label">Websites that show the chat</span>
        <textarea
          value={widget}
          onChange={(e) => setWidget(e.target.value)}
          rows={3}
          placeholder="https://www.acme.com"
        />
        <span className="admin-help muted">One per line, just the address: https://www.acme.com</span>
      </label>
      <label>
        <span className="admin-label">Dashboards that show the agent console</span>
        <textarea
          value={consoleSites}
          onChange={(e) => setConsoleSites(e.target.value)}
          rows={2}
          placeholder="https://admin.acme.com"
        />
      </label>
      {error && (
        <p className="error-text" role="alert">
          {error}
        </p>
      )}
      <div className="admin-actions">
        {saved && (
          <span className="muted" role="status">
            Saved.
          </span>
        )}
        <button type="submit" className="button" disabled={busy}>
          {tenant ? "Save changes" : "Create company"}
        </button>
      </div>
    </form>
  );
}

// "Allow images", with whether the server's storage for them works.
function ImagesField({ api, value, onChange }: { api: AdminApi; value: boolean; onChange: (v: boolean) => void }) {
  const [storage, setStorage] = useState<StorageStatus | null>(null);

  useEffect(() => {
    api
      .storage()
      .then(setStorage)
      .catch(() => setStorage({ ok: false, store: "?", problem: "Couldn't check the storage." }));
  }, [api]);

  return (
    <div className="admin-field">
      <span className="admin-label">Images</span>
      <label className="admin-check">
        <input type="checkbox" checked={value} onChange={(e) => onChange(e.target.checked)} />
        Customers and agents can send images (PNG, JPEG, GIF or WebP, up to 5 MB)
      </label>
      {storage && (
        <span className={storage.ok ? "admin-help muted" : "admin-help error-text"} role="status">
          {storage.ok
            ? `Storage works (${storage.store}).`
            : `Storage isn't working (${storage.store}): ${storage.problem}. The image button stays hidden until it does.`}
        </span>
      )}
    </div>
  );
}

const DAYS = [
  ["MON", "Monday"],
  ["TUE", "Tuesday"],
  ["WED", "Wednesday"],
  ["THU", "Thursday"],
  ["FRI", "Friday"],
  ["SAT", "Saturday"],
  ["SUN", "Sunday"],
] as const;

const NINE_TO_FIVE = "09:00-17:00";
const WEEKDAYS: OpeningHours["days"] = {
  MON: NINE_TO_FIVE,
  TUE: NINE_TO_FIVE,
  WED: NINE_TO_FIVE,
  THU: NINE_TO_FIVE,
  FRI: NINE_TO_FIVE,
};

// Opening hours: off means always open. Turning it on starts with weekdays 9 to 5 in the admin's time zone.
function HoursField({ value, onChange }: { value: OpeningHours | null; onChange: (v: OpeningHours | null) => void }) {
  function setDay(day: string, range: string | null) {
    if (!value) return;
    const days = { ...value.days };
    if (range) days[day] = range;
    else delete days[day];
    onChange({ ...value, days });
  }

  return (
    <fieldset className="admin-hours">
      <legend className="admin-label">Opening hours</legend>
      <label className="admin-check">
        <input
          type="checkbox"
          checked={value !== null}
          onChange={(e) =>
            onChange(
              e.target.checked ? { timezone: Intl.DateTimeFormat().resolvedOptions().timeZone, days: WEEKDAYS } : null,
            )
          }
        />
        Only answer chats at set times
      </label>
      <span className="admin-help muted">
        Outside these hours the chat says the team is away and when it's back. Chats still reach the queue.
      </span>
      {value && (
        <>
          <label>
            <span className="admin-label">Time zone</span>
            <input
              value={value.timezone}
              onChange={(e) => onChange({ ...value, timezone: e.target.value })}
              placeholder="Europe/Berlin"
              required
            />
          </label>
          {DAYS.map(([day, label]) => {
            const range = value.days[day];
            const [from, to] = (range ?? NINE_TO_FIVE).split("-");
            return (
              <div className="admin-day" key={day}>
                <label className="admin-check">
                  <input
                    type="checkbox"
                    checked={!!range}
                    onChange={(e) => setDay(day, e.target.checked ? `${from}-${to}` : null)}
                  />
                  {label}
                </label>
                {range ? (
                  <span className="admin-times">
                    <input
                      type="time"
                      value={from}
                      onChange={(e) => setDay(day, `${e.target.value}-${to}`)}
                      aria-label={`${label} from`}
                      required
                    />
                    <span className="muted">to</span>
                    <input
                      type="time"
                      value={to}
                      onChange={(e) => setDay(day, `${from}-${e.target.value}`)}
                      aria-label={`${label} until`}
                      required
                    />
                  </span>
                ) : (
                  <span className="muted">Closed</span>
                )}
              </div>
            );
          })}
        </>
      )}
    </fieldset>
  );
}

function CopyButton({ text, label }: { text: string; label: string }) {
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!copied) return;
    const timer = setTimeout(() => setCopied(false), 1500);
    return () => clearTimeout(timer);
  }, [copied]);

  return (
    <button
      type="button"
      className="icon-button quiet copy-button"
      aria-label={copied ? "Copied" : label}
      title={copied ? "Copied" : "Copy"}
      onClick={() =>
        navigator.clipboard?.writeText(text).then(
          () => setCopied(true),
          () => {},
        )
      }
    >
      {copied ? <CheckIcon size={14} /> : <CopyIcon size={14} />}
    </button>
  );
}
