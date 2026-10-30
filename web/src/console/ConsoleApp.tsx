import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { agentApi, ApiError, type ConversationSummary, type Me, type TenantItem } from "../lib/api";
import { postToHost } from "../lib/bridge";
import { BellIcon, CloseIcon, ExpandIcon } from "../lib/icons";
import { chime, setSoundEnabled, soundEnabled } from "../lib/sound";
import { openSocket, type QueueSnapshot, type Socket } from "../lib/realtime";
import { ConversationView } from "./ConversationView";
import { ago, initials, priorityLabel, reasonLabel } from "./format";
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
  onSignOut?: () => void;
  onClose?: () => void;
  // Opens the host's full-page console, on this conversation if one is open.
  onExpand?: (conversationId: string | null) => void;
  initialConversation?: string | null;
}

// A conversation id from the host is only used if it looks like one.
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function ConsoleApp({ getToken, onUnauthorized, onSignOut, onClose, onExpand, initialConversation }: Props) {
  const api = useMemo(() => agentApi(getToken), [getToken]);
  const [me, setMe] = useState<Me | null>(null);
  const [tenants, setTenants] = useState<TenantItem[]>([]);
  const [socket, setSocket] = useState<Socket | null>(null);
  const [tab, setTab] = useState<Tab>("queue");
  const [counts, setCounts] = useState({ waiting: 0, mine: 0 });
  const [selected, setSelected] = useState<string | null>(
    initialConversation && UUID.test(initialConversation) ? initialConversation : null,
  );
  const [error, setError] = useState<string | null>(null);
  const [sound, setSound] = useState(soundEnabled);
  const list = useConversationList(api, tab, me?.sub ?? null, tenants);

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
    countTimer.current = setTimeout(() => api.counts().then(setCounts).catch(() => {}), 300);
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
  const live = useRef({ ...list, refreshCounts, sound, selected });
  live.current = { ...list, refreshCounts, sound, selected };

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
        if (event.type === "conversation.activity") {
          live.current.activity(event.data, live.current.selected);
          return;
        }
        if (event.type === "conversation.waiting" && live.current.sound) chime();
        live.current.apply(event.data as unknown as QueueSnapshot);
        live.current.refreshCounts();
      }),
    );
    return () => stops.forEach((stop) => stop());
  }, [socket, tenants]);

  useEffect(() => {
    document.title = counts.waiting > 0 ? `(${counts.waiting}) Quokra console` : "Quokra console";
    postToHost({ type: "console.unread", count: counts.waiting });
  }, [counts.waiting]);

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
          <span className="avatar avatar-agent">{initials(me?.name ?? null)}</span>
          <div className="pane-title">
            <strong>{me?.name ?? "…"}</strong>
            <span className="muted">Support</span>
          </div>
          <button
            type="button"
            className="icon-button quiet"
            aria-label={sound ? "Mute new-chat sound" : "Play a sound for new chats"}
            aria-pressed={sound}
            onClick={() => {
              setSoundEnabled(!sound);
              setSound(!sound);
              if (!sound) chime();
            }}
          >
            <BellIcon size={18} off={!sound} />
          </button>
          {onSignOut && (
            <button type="button" className="link-button" onClick={onSignOut}>
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
        {error && (
          <p className="console-error" role="alert">
            {error}{" "}
            <button type="button" className="link-button" onClick={() => (setError(null), list.refresh())}>
              Retry
            </button>
          </p>
        )}
        <ul className="console-list">
          {list.items.length === 0 && !list.loading && <li className="empty muted">{emptyText(tab)}</li>}
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
            onChanged={refreshCounts}
            onError={failed}
          />
        ) : (
          <div className="console-empty">
            <p className="muted">Pick a conversation to start.</p>
          </div>
        )}
      </section>
    </main>
  );
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
          <span className="muted item-time">{ago(c.waitingSince ?? c.lastMessageAt)}</span>
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
