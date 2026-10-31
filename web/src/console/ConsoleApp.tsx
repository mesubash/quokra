import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { adminApi, agentApi, ApiError, type ConversationSummary, type FreshToken, type Me, type TenantItem } from "../lib/api";
import { onHostMessage, postToHost } from "../lib/bridge";
import { accent, applyColor } from "../lib/theme";
import { CloseIcon, ExpandIcon, SearchIcon, SettingsIcon } from "../lib/icons";
import { loadPrefs, savePrefs, type NotifyPrefs } from "../lib/notifyPrefs";
import { chime } from "../lib/sound";
import { NotifyMenu, type HostNotifications } from "./NotifyMenu";
import { PresenceMenu, usePresence } from "./PresenceMenu";
import { noticeFor, type Notice } from "./notifications";
import { openSocket, type QueueSnapshot, type Socket } from "../lib/realtime";
import { ConversationView } from "./ConversationView";
import { Settings, useAdmin } from "./admin/AdminArea";
import { ago, initials, listTime, priorityLabel, reasonLabel } from "./format";
import { useConversationList, type Tab } from "./useConversationList";

const TABS: { id: Tab; label: string }[] = [
  { id: "queue", label: "Queue" },
  { id: "mine", label: "Mine" },
  { id: "resolved", label: "Resolved" },
];

interface Props {
  getToken: () => string;
  // The token was rejected: sign out (standalone) or ask the dashboard for a new one (embedded).
  onUnauthorized: () => void;
  // Asks the dashboard for a new token when a call gets a 401, so it can be retried once.
  freshToken?: FreshToken;
  onSignOut?: () => void;
  onClose?: () => void;
  // Opens the host's full-page console, on this conversation if one is open.
  onExpand?: (conversationId: string | null) => void;
  initialConversation?: string | null;
}

// A conversation id from the host is only used if it looks like one.
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function ConsoleApp({ getToken, freshToken, onUnauthorized, onSignOut, onClose, onExpand, initialConversation }: Props) {
  const api = useMemo(() => agentApi(getToken, freshToken), [getToken, freshToken]);
  const admins = useMemo(() => adminApi(getToken, freshToken), [getToken, freshToken]);
  // Settings (admins only) open over the whole console.
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [me, setMe] = useState<Me | null>(null);
  const [tenants, setTenants] = useState<TenantItem[]>([]);
  useCompanyColor(tenants);
  const [socket, setSocket] = useState<Socket | null>(null);
  const [tab, setTab] = useState<Tab>("queue");
  const [counts, setCounts] = useState({ waiting: 0, mine: 0, myUnread: 0 });
  const [selected, setSelected] = useState<string | null>(
    initialConversation && UUID.test(initialConversation) ? initialConversation : null,
  );
  const [error, setError] = useState<string | null>(null);
  const [prefs, setPrefsState] = useState<NotifyPrefs>(loadPrefs);
  const setPrefs = (next: NotifyPrefs) => {
    savePrefs(next);
    setPrefsState(next);
  };
  // What the dashboard says about browser notifications (sdk.js, embedded only).
  const [hostNotify, setHostNotify] = useState<HostNotifications | null>(null);
  const [toasts, setToasts] = useState<(Notice & { key: number })[]>([]);
  const [bannerDismissed, setBannerDismissed] = useState(() => {
    try {
      return localStorage.getItem("quokra.notify.banner") === "dismissed";
    } catch {
      return false;
    }
  });
  const [query, setQuery] = useState("");
  const search = useDebounced(query.trim(), 300);
  const list = useConversationList(api, tab, me?.sub ?? null, tenants, search);
  // Server admins manage every company; company admins the ones in manages.
  const manages = me !== null && (me.admin || me.manages.length > 0);
  const admin = useAdmin(admins, manages);
  // Admins without the agent role only get settings.
  const adminOnly = me !== null && manages && !me.agent;
  const presence = usePresence(api, !!me?.agent);
  const showSettings = settingsOpen || adminOnly;

  const failed = useCallback(
    (e: unknown) => {
      if (e instanceof ApiError && e.status === 401) onUnauthorized();
      else setError("Something went wrong. Try again.");
    },
    [onUnauthorized],
  );

  useEffect(() => {
    if (list.error) failed(list.error);
  }, [list.error, failed]);

  // Counts change with every queue event; a burst of events makes one request.
  const countTimer = useRef<ReturnType<typeof setTimeout>>(undefined);
  const refreshCounts = useCallback(() => {
    clearTimeout(countTimer.current);
    countTimer.current = setTimeout(
      () =>
        api
          .counts()
          .then(setCounts)
          .catch(() => {}),
      300,
    );
  }, [api]);

  useEffect(() => {
    Promise.all([api.me(), api.tenants()])
      .then(([m, t]) => {
        setMe(m);
        setTenants(t.items);
      })
      .catch(failed);
    refreshCounts();
  }, [api, failed, refreshCounts]);

  // The socket outlives tab switches, so it calls whatever the latest handlers are.
  // Sound, a toast in the console, and (through sdk.js) a browser notification if the dashboard isn't in front.
  const notify = useCallback(
    (notice: Notice) => {
      if (prefs.sound) chime(notice.kind);
      const key = Date.now() + Math.random();
      setToasts((current) => [...current.slice(-2), { ...notice, key }]);
      setTimeout(() => setToasts((current) => current.filter((t) => t.key !== key)), 6000);
      if (prefs.browser) postToHost({ type: "console.notify", ...notice });
    },
    [prefs],
  );

  const live = useRef({ ...list, refreshCounts, prefs, selected, me, notify });
  live.current = { ...list, refreshCounts, prefs, selected, me, notify };

  // One socket for the whole console: tenant queues here, the open chat in ConversationView.
  // After a reconnect, reload what's on screen, since events may have been missed.
  useEffect(() => {
    let connected = false;
    const s = openSocket(getToken, {
      onConnect: () => {
        if (connected) live.current.refresh();
        connected = true;
        live.current.refreshCounts();
      },
      onDisconnect: () => {},
    });
    setSocket(s);
    return () => s.close();
  }, [getToken]);

  useEffect(() => {
    if (!socket) return;
    const stops = tenants.map((t) =>
      socket.subscribe(`/topic/tenants/${t.id}/queue`, (event) => {
        const { me: who, prefs: p, selected: open } = live.current;
        // "Watching" only counts while the console is actually in front of the agent.
        const watching = document.hidden ? null : open;
        const notice = who ? noticeFor(event, who.sub, p, watching) : null;
        if (notice) live.current.notify(notice);

        if (event.type === "conversation.activity") {
          live.current.activity(event.data, open);
          if (event.data.assigneeSub === who?.sub) live.current.refreshCounts();
        } else if (event.type === "conversation.waiting" || event.type === "conversation.updated") {
          live.current.apply(event.data as unknown as QueueSnapshot);
          live.current.refreshCounts();
        } else if (event.type === "conversation.transferred") {
          live.current.refreshCounts();
        }
      }),
    );
    return () => stops.forEach((stop) => stop());
  }, [socket, tenants]);

  useEffect(() => {
    // The badge: people waiting, plus unread messages in my own chats.
    const badge = counts.waiting + counts.myUnread;
    document.title = badge > 0 ? `(${badge}) Quokra console` : "Quokra console";
    postToHost({ type: "console.unread", count: badge });
  }, [counts.waiting, counts.myUnread]);

  // sdk.js says whether it can show browser notifications, and opens a chat when one is clicked.
  // Asked once here, since this screen appears only after sign-in, later than the frame's "ready".
  useEffect(() => postToHost({ type: "console.notifications.check" }), []);
  useEffect(
    () =>
      onHostMessage((m) => {
        if (m.type === "console.notifications") setHostNotify({ available: m.available, permission: m.permission });
        else if (m.type === "console.open" && UUID.test(m.conversationId)) {
          setSettingsOpen(false);
          setSelected(m.conversationId);
        }
      }),
    [],
  );

  const showBanner =
    !bannerDismissed && prefs.browser && hostNotify?.available === true && hostNotify.permission === "default";
  const dismissBanner = () => {
    setBannerDismissed(true);
    try {
      localStorage.setItem("quokra.notify.banner", "dismissed");
    } catch {
      // Storage blocked: hidden until the page reloads.
    }
  };
  const askPermission = () => postToHost({ type: "console.notifications.request" });

  // Loads the next page when the end of the list scrolls into view.
  const sentinel = useRef<HTMLLIElement>(null);
  const { hasMore, loadMore } = list;
  useEffect(() => {
    const el = sentinel.current;
    if (!el || !hasMore || typeof IntersectionObserver === "undefined") return;
    const observer = new IntersectionObserver((entries) => entries[0].isIntersecting && loadMore());
    observer.observe(el);
    return () => observer.disconnect();
  }, [hasMore, loadMore]);

  function open(id: string) {
    setSelected(id);
    list.markSeen(id);
  }

  function badge(id: Tab) {
    const n = id === "queue" ? counts.waiting : id === "mine" ? counts.mine : 0;
    return n > 0 ? <span className="count">{n > 99 ? "99+" : n}</span> : null;
  }

  return (
    <main className={`console ${selected ? "has-selection" : ""}`}>
      <aside className="console-list-pane" aria-label="Conversations">
        <header className="pane-header">
          {me?.agent ? (
            <PresenceMenu name={me.name} status={presence.status} onChange={(s) => void presence.change(s)} />
          ) : (
            <span className="avatar avatar-agent">{initials(me?.name ?? null)}</span>
          )}
          <div className="pane-title">
            <strong>{me?.name ?? "…"}</strong>
            <span className="muted">
              {presence.status === "ONLINE" ? "Online" : presence.status === "AWAY" ? "Away" : "Support"}
            </span>
          </div>
          <NotifyMenu prefs={prefs} onChange={setPrefs} host={hostNotify} onTurnOn={askPermission} />
          {me && (
            <button
              type="button"
              className="icon-button quiet"
              aria-label="Settings"
              title="Settings"
              onClick={() => setSettingsOpen(true)}
            >
              <SettingsIcon size={17} />
            </button>
          )}
          {onSignOut && (
            <button
              type="button"
              className="link-button"
              onClick={() => void presence.change("AWAY").finally(onSignOut)}
            >
              Sign out
            </button>
          )}
          {onExpand && (
            <button
              type="button"
              className="icon-button quiet"
              aria-label="Open full view"
              title="Open full view"
              onClick={() => onExpand(selected)}
            >
              <ExpandIcon size={17} />
            </button>
          )}
          {onClose && (
            <button type="button" className="icon-button quiet" aria-label="Close console" onClick={onClose}>
              <CloseIcon size={18} />
            </button>
          )}
        </header>
        {showBanner && (
          <div className="notify-banner" role="status">
            <span>Get notified of new chats, even in other tabs.</span>
            <button type="button" className="button button-small" onClick={askPermission}>
              Turn on
            </button>
            <button type="button" className="icon-button quiet" aria-label="Not now" onClick={dismissBanner}>
              <CloseIcon size={14} />
            </button>
          </div>
        )}
        <nav className="segmented" role="tablist" aria-label="Lists">
          {TABS.map((t) => (
            <button
              key={t.id}
              type="button"
              role="tab"
              aria-selected={tab === t.id}
              onClick={() => {
                setError(null);
                setTab(t.id);
              }}
            >
              {t.label}
              {badge(t.id)}
            </button>
          ))}
        </nav>
        <div className="console-search">
          <SearchIcon size={15} />
          <input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search name, email or message"
            aria-label="Search conversations"
            maxLength={100}
          />
        </div>
        {error && (
          <p className="console-error" role="alert">
            {error}{" "}
            <button type="button" className="link-button" onClick={() => (setError(null), list.refresh())}>
              Retry
            </button>
          </p>
        )}
        <ul className="console-list">
          {list.items.length === 0 && !list.loading && (
            <li className="empty muted">{search ? "No conversations match your search." : emptyText(tab)}</li>
          )}
          {list.items.map((c) => (
            <li key={c.id}>
              <Row conversation={c} selected={selected === c.id} showTenant={tenants.length > 1} onSelect={open} />
            </li>
          ))}
          {list.loading && <li className="empty muted">Loading…</li>}
          {list.hasMore && !list.loading && (
            <li ref={sentinel}>
              <button type="button" className="load-more" onClick={list.loadMore}>
                Load more
              </button>
            </li>
          )}
        </ul>
      </aside>
      <section className="console-chat-pane">
        {selected && me && socket ? (
          <ConversationView
            key={selected}
            id={selected}
            api={api}
            me={me}
            socket={socket}
            onBack={() => setSelected(null)}
            onClose={onClose}
            onExpand={onExpand ? () => onExpand(selected) : undefined}
            onOpen={open}
            onChanged={refreshCounts}
            onError={failed}
            imagesAllowed={(slug) => tenants.some((t) => t.slug === slug && t.attachments)}
          />
        ) : (
          <div className="console-empty">
            <p className="muted">Pick a conversation to start.</p>
          </div>
        )}
      </section>
      {showSettings && (
        <Settings
          admin={manages ? admin : undefined}
          api={manages ? admins : undefined}
          canCreate={me?.admin ?? false}
          scope={SCOPE}
          notifications={{ prefs, onChange: setPrefs, host: hostNotify, onTurnOn: askPermission }}
          onClose={adminOnly ? undefined : () => setSettingsOpen(false)}
        />
      )}
      <div className="toasts" aria-live="polite">
        {toasts.map((t) => (
          <button
            key={t.key}
            type="button"
            className={`toast toast-${t.kind}`}
            onClick={() => {
              setToasts((current) => current.filter((x) => x.key !== t.key));
              setSettingsOpen(false);
              open(t.conversationId);
            }}
          >
            <strong>{t.title}</strong>
            <span>{t.body}</span>
          </button>
        ))}
      </div>
    </main>
  );
}

// The company this console was embedded for (sdk.js passes ?tenant=). Its settings show only that company;
// Quokra's own console page, without one, can switch between companies and create them.
const SCOPE = new URLSearchParams(window.location.search).get("tenant") || null;

// The console embedded for one company wears its colour, unless the dashboard passed its own accent.
function useCompanyColor(tenants: TenantItem[]) {
  const color = SCOPE && !accent ? tenants.find((t) => t.slug === SCOPE)?.primaryColor : null;
  useEffect(() => {
    if (!color) return;
    applyColor(color);
    postToHost({ type: "console.color", color });
  }, [color]);
}

// The value once it has stopped changing for a moment, so typing doesn't send a request per key.
function useDebounced(value: string, ms: number) {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), ms);
    return () => clearTimeout(timer);
  }, [value, ms]);
  return settled;
}

function emptyText(tab: Tab) {
  if (tab === "queue") return "No one is waiting. Nice.";
  if (tab === "mine") return "You have no open chats.";
  return "No resolved chats yet.";
}

function Row({
  conversation: c,
  selected,
  showTenant,
  onSelect,
}: {
  conversation: ConversationSummary;
  selected: boolean;
  showTenant: boolean;
  onSelect: (id: string) => void;
}) {
  const name = c.customerName ?? "Guest";
  return (
    <button
      type="button"
      className={`console-item ${selected ? "selected" : ""}`}
      aria-current={selected || undefined}
      onClick={() => onSelect(c.id)}
    >
      <span className="avatar avatar-customer">{initials(name)}</span>
      <span className="item-body">
        <span className="item-top">
          <strong>{name}</strong>
          <span className="muted item-time" title={`${ago(c.waitingSince ?? c.lastMessageAt)} ago`}>
            {listTime(c.lastMessageAt)}
          </span>
        </span>
        <span className="item-middle">
          {c.lastMessagePreview && (
            <span className={`item-preview ${c.unreadCount > 0 ? "unread" : ""}`}>{c.lastMessagePreview}</span>
          )}
          {c.unreadCount > 0 && (
            <span className="count" aria-label={`${c.unreadCount} unread`}>
              {c.unreadCount > 99 ? "99+" : c.unreadCount}
            </span>
          )}
        </span>
        <span className="item-tags">
          {c.priority !== "NORMAL" && (
            <span className={`badge badge-${c.priority.toLowerCase()}`}>{priorityLabel(c.priority)}</span>
          )}
          {c.status === "WAITING" && c.handoffReason && <span className="badge">{reasonLabel(c.handoffReason)}</span>}
          {c.status !== "WAITING" && c.assignee && <span className="badge">{c.assignee.name}</span>}
          {showTenant && <span className="badge">{c.tenantSlug}</span>}
        </span>
      </span>
    </button>
  );
}
