import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from "react";
import {
  ApiError,
  type AdminAgents,
  type AdminApi,
  type AdminTenant,
  type OpeningHours,
  type RetentionLimits,
  type SavedReply,
  type StorageStatus,
  type TenantForm,
} from "../../lib/api";
import { CheckIcon, CloseIcon, CopyIcon } from "../../lib/icons";
import { initials } from "../format";
import { NotifyOptions, type NotifyOptionsProps } from "../NotifyMenu";
import { BotCard } from "./BotCard";
import { Row } from "./Row";

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

const INTRO: Record<Section, (company: string) => string> = {
  notifications: () => "What alerts you, and how. Kept in this browser.",
  general: (c) => `How ${c} looks in the chat, and when chats are answered.`,
  bot: () => "The questions and answers customers can pick from before they reach a person.",
  replies: () => "Answers agents insert by typing / in the reply box. {name} becomes the customer's name.",
  agents: (c) => `People who answer chats for ${c}. They sign in with your own login and need the agent role there.`,
  embed: () => "Code for the website and the dashboard, and the key for identifying signed-in customers.",
};

// Settings, opened from the gear in the console header. Everyone gets their notifications; admins also get the
// company's sections. A console embedded for one company (scope) only ever shows that company; the company
// picker and "New company" are for Quokra's own console page. onClose is missing for admins without the agent
// role, who only have settings.
export function Settings({
  admin,
  api,
  scope,
  notifications,
  onClose,
}: {
  admin?: Admin;
  api?: AdminApi;
  scope?: string | null;
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

  const tenants = (admin?.tenants ?? []).filter((t) => !scope || t.slug === scope);
  const selected = scope ?? admin?.selected ?? tenants[0]?.slug ?? null;
  const tenant = tenants.find((t) => t.slug === selected);
  const creating = !scope && selected === "new";
  // General first, then your own notifications, then the rest of the company's sections.
  const sections: { id: Section; label: string }[] =
    admin && api && tenant
      ? [COMPANY_SECTIONS[0], { id: "notifications", label: "Notifications" }, ...COMPANY_SECTIONS.slice(1)]
      : [{ id: "notifications", label: "Notifications" }];
  const title = creating ? "New company" : (COMPANY_SECTIONS.find((x) => x.id === section)?.label ?? "Notifications");

  function go(next: Section) {
    setSection(next);
    if (creating) admin?.select(null);
  }

  return (
    <div className="settings" role="dialog" aria-modal="true" aria-label="Settings">
      <aside className="settings-side">
        <div className="settings-side-head">
          <h2>Settings</h2>
          {onClose && (
            <button type="button" className="icon-button quiet" aria-label="Close settings" onClick={onClose}>
              <CloseIcon size={18} />
            </button>
          )}
        </div>
        <nav className="settings-nav" aria-label="Settings sections">
          {admin && api && !scope && tenants.length > 1 && (
            <select
              className="settings-company"
              aria-label="Company"
              value={creating ? "" : (selected ?? "")}
              onChange={(e) => admin.select(e.target.value)}
            >
              {creating && <option value="">New company</option>}
              {tenants.map((t) => (
                <option key={t.slug} value={t.slug}>
                  {t.displayName}
                </option>
              ))}
            </select>
          )}
          {sections.map((x) => (
            <NavItem key={x.id} current={!creating && section === x.id} onClick={() => go(x.id)}>
              {x.label}
            </NavItem>
          ))}
          {admin && api && !scope && (
            <button type="button" className="settings-new" onClick={() => admin.select("new")}>
              + New company
            </button>
          )}
        </nav>
      </aside>

      <div className="settings-main" key={`${selected} ${section}`}>
        <header className="settings-head">
          <h3>{title}</h3>
          {!creating && <p>{INTRO[section](tenant?.displayName ?? "the company")}</p>}
        </header>
        {admin?.error && section !== "notifications" && (
          <p className="console-error" role="alert">
            {admin.error}{" "}
            <button type="button" className="link-button" onClick={admin.reload}>
              Retry
            </button>
          </p>
        )}
        {section === "notifications" && !creating ? (
          <div className="settings-notify">
            <NotifyOptions {...notifications} explained />
          </div>
        ) : !admin || !api ? null : creating ? (
          <TenantFormView
            api={api}
            bots={admin.bots}
            onSaved={(t) => {
              admin.reload();
              admin.select(t.slug);
              setSection("embed");
            }}
          />
        ) : !tenant ? (
          <p className="muted">{admin.tenants === null ? "Loading…" : "No companies yet."}</p>
        ) : (
          <>
            {section === "general" && (
              <TenantFormView api={api} bots={admin.bots} tenant={tenant} onSaved={admin.reload} />
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
  );
}

function NavItem({ current, onClick, children }: { current: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button type="button" aria-current={current ? "page" : undefined} onClick={onClick}>
      {children}
    </button>
  );
}

// The publishable key and ready-to-paste snippets. The key is public, so it's fine to show.
function EmbedCard({ tenant }: { tenant: AdminTenant }) {
  const origin = window.location.origin;
  const chat = `<script src="${origin}/sdk.js"\n        data-quokra-chat="${tenant.slug}"\n        data-quokra-pk="${tenant.publishableKey}"\n        async></script>`;
  const consoleSnippet = `<div id="support" style="height: 100vh"></div>\n<script src="${origin}/sdk.js"></script>\n<script>\n  Quokra.console.mount("#support", {\n    tenant: "${tenant.slug}",\n    getToken: () => auth.getAccessToken(), // your dashboard's login\n  });\n</script>`;
  return (
    <section className="settings-group">
      {(tenant.widgetOrigins.length === 0 || tenant.consoleOrigins.length === 0) && (
        <p className="notice admin-notice">
          Add the company's sites under General, or browsers will refuse to show the chat or console there.
        </p>
      )}
      <Row label="Publishable key" help="Public. Safe to put in web pages.">
        <span className="admin-value">
          <code>{tenant.publishableKey}</code>
          <CopyButton text={tenant.publishableKey} label="Copy publishable key" />
        </span>
      </Row>
      <Row label="Chat" help="Before </body> on every page of the website.">
        <Snippet label="Chat snippet" code={chat} />
      </Row>
      <Row label="Console" help="On the dashboard page where agents answer.">
        <Snippet label="Console snippet" code={consoleSnippet} />
      </Row>
    </section>
  );
}

function Snippet({ label, code }: { label: string; code: string }) {
  return (
    <div className="admin-snippet">
      <pre>
        <code>{code}</code>
      </pre>
      <CopyButton text={code} label={`Copy ${label.toLowerCase()}`} />
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
    <section className="settings-group">
      <Row
        label="Secret key"
        help="Only for identifying signed-in customers. The company's backend signs a short token with it; keep it there."
      >
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
      </Row>
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
    <section className="settings-group">
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
              <strong>
                {a.name} <span className={a.online ? "presence presence-online" : "presence"}>{a.online ? "Online" : "Away"}</span>
              </strong>
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
    <section className="settings-group">
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
      <form className="admin-form admin-reply-form" onSubmit={save}>
        <h4>{form.id ? "Edit reply" : "New reply"}</h4>
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
  const [retention, setRetention] = useState<number | undefined>(tenant?.retentionMonths);
  const [widget, setWidget] = useState<string[]>(tenant?.widgetOrigins ?? []);
  const [consoleSites, setConsoleSites] = useState<string[]>(tenant?.consoleOrigins ?? []);
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
      retentionMonths: retention,
      widgetOrigins: widget,
      consoleOrigins: consoleSites,
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
    <form className="settings-form" onSubmit={submit}>
      <section className="settings-group">
        <h4>Company</h4>
        {!tenant && (
          <Row label="Slug" help="Used in the embed code. Lowercase letters, digits and dashes; can't change later.">
            <input
              value={slug}
              onChange={(e) => setSlug(e.target.value.toLowerCase())}
              placeholder="acme-shop"
              required
              pattern="[a-z0-9][a-z0-9-]{0,38}[a-z0-9]"
              aria-label="Slug"
            />
          </Row>
        )}
        <Row label="Name" help="Shown in the chat header.">
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Acme"
            required
            maxLength={80}
            aria-label="Name"
          />
        </Row>
        <Row label="Brand colour" help="Buttons and the chat header.">
          <span className="admin-color">
            <input
              type="color"
              value={/^#[0-9a-fA-F]{6}$/.test(color) ? color : "#0051fb"}
              onChange={(e) => setColor(e.target.value)}
              aria-label="Pick a colour"
            />
            <input
              value={color}
              onChange={(e) => setColor(e.target.value)}
              placeholder="#0051fb"
              maxLength={7}
              aria-label="Brand colour"
            />
          </span>
        </Row>
        <Row label="Logo" help="An https:// image, shown in the chat header. Optional.">
          <input
            value={logo}
            onChange={(e) => setLogo(e.target.value)}
            placeholder="https://www.acme.com/logo.png"
            aria-label="Logo URL"
          />
        </Row>
      </section>

      <section className="settings-group">
        <h4>Chat</h4>
        <Row label="Bot file" help="Where the bot's questions start from. Change the questions under Bot.">
          <select value={bot} onChange={(e) => setBot(e.target.value)} required aria-label="Bot file">
            {bots.map((b) => (
              <option key={b} value={b}>
                {b}
              </option>
            ))}
          </select>
        </Row>
        <Row
          label="Close quiet chats"
          help="Chats the customer left, or where the agent wrote last. Waiting chats never close by themselves."
        >
          <select
            value={autoResolve}
            onChange={(e) => setAutoResolve(Number(e.target.value))}
            aria-label="Close quiet chats"
          >
            {AUTO_RESOLVE.some((o) => o.minutes === autoResolve) ? null : (
              <option value={autoResolve}>After {autoResolve} minutes</option>
            )}
            {AUTO_RESOLVE.map((o) => (
              <option key={o.minutes} value={o.minutes}>
                {o.label}
              </option>
            ))}
          </select>
        </Row>
        <HoursField value={hours} onChange={setHours} />
        <ImagesField api={api} value={images} onChange={setImages} />
        <RetentionField api={api} value={retention} onChange={setRetention} />
      </section>

      <section className="settings-group">
        <h4>Sites</h4>
        <Row label="Websites" help="Where the chat may appear. Include www. and staging sites you use.">
          <SitesField value={widget} onChange={setWidget} placeholder="https://www.acme.com" name="website" />
        </Row>
        <Row label="Dashboards" help="Where the agent console may appear.">
          <SitesField
            value={consoleSites}
            onChange={setConsoleSites}
            placeholder="https://admin.acme.com"
            name="dashboard"
          />
        </Row>
      </section>

      <div className="settings-save">
        {error ? (
          <p className="error-text" role="alert">
            {error}
          </p>
        ) : (
          saved && (
            <span className="muted" role="status">
              Saved.
            </span>
          )
        )}
        <button type="submit" className="button" disabled={busy}>
          {tenant ? "Save" : "Create company"}
        </button>
      </div>
    </form>
  );
}

// A list of site addresses: one per line with Remove, and a field to add more (pasting several works too).
// Only the address is kept (https://www.acme.com); the server checks the rest.
function SitesField({
  value,
  onChange,
  placeholder,
  name,
}: {
  value: string[];
  onChange: (sites: string[]) => void;
  placeholder: string;
  name: string;
}) {
  const [draft, setDraft] = useState("");

  function add() {
    const added = lines(draft).map((site) => site.replace(/\/+$/, ""));
    if (added.length === 0) return;
    onChange([...value, ...added.filter((site) => !value.includes(site))]);
    setDraft("");
  }

  return (
    <div className="sites">
      {value.length > 0 && (
        <ul className="sites-list">
          {value.map((site) => (
            <li key={site}>
              <code>{site}</code>
              <button type="button" className="link-button" onClick={() => onChange(value.filter((s) => s !== site))}>
                Remove
              </button>
            </li>
          ))}
        </ul>
      )}
      <div className="sites-add">
        <input
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              add();
            }
          }}
          onBlur={add}
          placeholder={placeholder}
          aria-label={`Add a ${name}`}
        />
        <button type="button" className="button button-quiet" disabled={!draft.trim()} onClick={add}>
          Add
        </button>
      </div>
    </div>
  );
}

const RETENTION_CHOICES = [1, 3, 6, 12, 24, 36, 60];

export function retentionChoices(limits: RetentionLimits, current?: number) {
  const allowed = (m: number) => m >= limits.minMonths && (limits.maxMonths === 0 || m <= limits.maxMonths);
  const months = RETENTION_CHOICES.filter(allowed);
  // A value set by hand (e.g. 18 months) stays selectable while it's within the limits.
  if (current !== undefined && current > 0 && allowed(current) && !months.includes(current)) months.push(current);
  months.sort((a, b) => a - b);
  return limits.maxMonths === 0 ? [...months, 0] : months;
}

const monthsLabel = (m: number) =>
  m === 0 ? "Forever" : m % 12 === 0 ? `${m / 12} year${m === 12 ? "" : "s"}` : `${m} month${m === 1 ? "" : "s"}`;

// How long resolved chats are kept, within what the server allows.
function RetentionField({
  api,
  value,
  onChange,
}: {
  api: AdminApi;
  value: number | undefined;
  onChange: (months: number) => void;
}) {
  const [limits, setLimits] = useState<RetentionLimits | null>(null);

  useEffect(() => {
    api
      .retention()
      .then(setLimits)
      .catch(() => setLimits(null));
  }, [api]);

  if (!limits) return null;
  const choices = retentionChoices(limits, value);
  const current = value ?? (choices.includes(12) ? 12 : choices[choices.length - 1]);
  return (
    <Row
      label="Keep chats for"
      help="Resolved chats older than this are deleted at night, with their messages and images. Open chats are kept."
    >
      <select value={current} onChange={(e) => onChange(Number(e.target.value))} aria-label="Keep chats for">
        {choices.map((m) => (
          <option key={m} value={m}>
            {monthsLabel(m)}
          </option>
        ))}
      </select>
    </Row>
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
    <Row label="Images" help="PNG, JPEG, GIF or WebP, up to 5 MB, from customers and agents.">
      <label className="admin-check">
        <input type="checkbox" role="switch" checked={value} onChange={(e) => onChange(e.target.checked)} />
        Allow images
      </label>
      {storage && (
        <p className={storage.ok ? "setting-note" : "setting-note error-text"} role="status">
          {storage.ok
            ? `Storage is working (${storage.store}).`
            : `Storage isn't working (${storage.store}): ${storage.problem}. The image button stays hidden until it is.`}
        </p>
      )}
    </Row>
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
    <Row
      label="Opening hours"
      help="Outside them the chat says the team is away and when it's back. Chats still reach the queue."
    >
      <label className="admin-check">
        <input
          type="checkbox"
          role="switch"
          checked={value !== null}
          onChange={(e) =>
            onChange(
              e.target.checked ? { timezone: Intl.DateTimeFormat().resolvedOptions().timeZone, days: WEEKDAYS } : null,
            )
          }
        />
        Only at set times
      </label>
      {value && (
        <div className="admin-hours">
          <label className="admin-day">
            <span>Time zone</span>
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
                    role="switch"
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
        </div>
      )}
    </Row>
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
