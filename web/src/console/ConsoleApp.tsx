import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { agentApi, ApiError, type ConversationSummary, type Me, type TenantItem } from "../lib/api";
import { postToHost } from "../lib/bridge";
import { CloseIcon } from "../lib/icons";
import { openSocket, type Socket } from "../lib/realtime";
import { ConversationView } from "./ConversationView";
import { ago, priorityLabel, reasonLabel } from "./format";

type Tab = "queue" | "mine" | "resolved";

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
}

export function ConsoleApp({ getToken, onUnauthorized, onSignOut, onClose }: Props) {
  const api = useMemo(() => agentApi(getToken), [getToken]);
  const [me, setMe] = useState<Me | null>(null);
  const [tenants, setTenants] = useState<TenantItem[]>([]);
  const [socket, setSocket] = useState<Socket | null>(null);
  const [tab, setTab] = useState<Tab>("queue");
  const [items, setItems] = useState<ConversationSummary[]>([]);
  const [waiting, setWaiting] = useState(0);
  const [selected, setSelected] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const failed = useCallback(
    (e: unknown) => {
      if (e instanceof ApiError && e.status === 401) onUnauthorized();
      else setError("Something went wrong. Try again.");
    },
    [onUnauthorized],
  );

  const reload = useCallback(() => {
    const status = tab === "queue" ? "WAITING" : tab === "mine" ? "HUMAN" : "RESOLVED";
    api.list(status, tab === "mine").then((page) => setItems(page.items)).catch(failed);
    api.list("WAITING").then((page) => setWaiting(page.items.length)).catch(() => {});
  }, [api, tab, failed]);

  useEffect(() => {
    Promise.all([api.me(), api.tenants()])
      .then(([m, t]) => {
        setMe(m);
        setTenants(t.items);
      })
      .catch(failed);
  }, [api, failed]);

  useEffect(reload, [reload]);

  // The socket stays open across tab switches; it always calls the latest reload.
  const reloadRef = useRef(reload);
  reloadRef.current = reload;

  // One socket for the whole console: tenant queues here, the open chat in ConversationView.
  useEffect(() => {
    const s = openSocket(getToken, { onConnect: () => reloadRef.current(), onDisconnect: () => {} });
    setSocket(s);
    return () => s.close();
  }, [getToken]);

  useEffect(() => {
    if (!socket) return;
    const stops = tenants.map((t) => socket.subscribe(`/topic/tenants/${t.id}/queue`, () => reloadRef.current()));
    return () => stops.forEach((stop) => stop());
  }, [socket, tenants]);

  useEffect(() => {
    document.title = waiting > 0 ? `(${waiting}) Quokra console` : "Quokra console";
    postToHost({ type: "console.unread", count: waiting });
  }, [waiting]);

  return (
    <main className="console">
      <aside className="console-sidebar">
        <header className="console-me">
          <strong>{me?.name ?? "…"}</strong>
          {onSignOut && (
            <button type="button" className="link-button" onClick={onSignOut}>
              Sign out
            </button>
          )}
          {onClose && (
            <button type="button" className="icon-button console-close" aria-label="Close console" onClick={onClose}>
              <CloseIcon size={18} />
            </button>
          )}
        </header>
        <nav className="console-tabs" role="tablist">
          {TABS.map((t) => (
            <button
              key={t.id}
              type="button"
              role="tab"
              aria-selected={tab === t.id}
              className={tab === t.id ? "active" : ""}
              onClick={() => setTab(t.id)}
            >
              {t.label}
              {t.id === "queue" && waiting > 0 && <span className="count">{waiting}</span>}
            </button>
          ))}
        </nav>
        {error && <p className="error-text console-error">{error}</p>}
        <ul className="console-list">
          {items.length === 0 && <li className="empty muted">{tab === "queue" ? "No one is waiting." : "Nothing here."}</li>}
          {items.map((c) => (
            <li key={c.id}>
              <button
                type="button"
                className={`console-item ${selected === c.id ? "selected" : ""}`}
                onClick={() => setSelected(c.id)}
              >
                <span className="item-top">
                  <strong>{c.customerName ?? "Guest"}</strong>
                  <span className="muted">{ago(c.waitingSince ?? c.lastMessageAt)}</span>
                </span>
                <span className="item-preview">{c.lastMessagePreview}</span>
                <span className="item-tags">
                  {c.priority !== "NORMAL" && (
                    <span className={`badge badge-${c.priority.toLowerCase()}`}>{priorityLabel(c.priority)}</span>
                  )}
                  {c.handoffReason && <span className="badge">{reasonLabel(c.handoffReason)}</span>}
                  {tenants.length > 1 && <span className="badge">{c.tenantSlug}</span>}
                </span>
              </button>
            </li>
          ))}
        </ul>
      </aside>
      <section className="console-main">
        {selected && me && socket ? (
          <ConversationView key={selected} id={selected} api={api} me={me} socket={socket} onChanged={reload} onError={failed} />
        ) : (
          <p className="console-placeholder muted">Pick a conversation from the list.</p>
        )}
      </section>
    </main>
  );
}
