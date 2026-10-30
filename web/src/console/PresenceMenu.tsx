import { useCallback, useEffect, useRef, useState } from "react";
import type { AgentApi, PresenceStatus } from "../lib/api";
import { initials } from "./format";

const CHECK_IN_MS = 60_000;

// The agent's status: Online (answering chats) or Away. The open console checks in every minute, so an
// agent who closes it stops counting as online after a couple of minutes without doing anything.
export function usePresence(api: AgentApi, enabled: boolean) {
  const [status, setStatus] = useState<PresenceStatus | null>(null);

  useEffect(() => {
    if (!enabled) return;
    const checkIn = () =>
      api
        .presence()
        .then((r) => setStatus(r.status))
        .catch(() => {});
    checkIn();
    const timer = setInterval(checkIn, CHECK_IN_MS);
    return () => clearInterval(timer);
  }, [api, enabled]);

  const change = useCallback(
    (next: PresenceStatus) => {
      setStatus(next);
      return api
        .presence(next)
        .then((r) => setStatus(r.status))
        .catch(() => {});
    },
    [api],
  );

  return { status, change };
}

const CHOICES: { status: PresenceStatus; label: string; help: string }[] = [
  { status: "ONLINE", label: "Online", help: "Customers see the team is here." },
  { status: "AWAY", label: "Away", help: "The chat says the team is away. Chats still reach the queue." },
];

// The agent's avatar in the console header, with a dot for their status and a menu to change it.
export function PresenceMenu({
  name,
  status,
  onChange,
}: {
  name: string | null;
  status: PresenceStatus | null;
  onChange: (status: PresenceStatus) => void;
}) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const close = (e: Event) => {
      if (e instanceof KeyboardEvent ? e.key === "Escape" : !root.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", close);
    document.addEventListener("keydown", close);
    return () => {
      document.removeEventListener("mousedown", close);
      document.removeEventListener("keydown", close);
    };
  }, [open]);

  const label = status === "ONLINE" ? "Online" : status === "AWAY" ? "Away" : "Status";
  return (
    <div className="menu presence-menu" ref={root}>
      <button
        type="button"
        className="presence-avatar"
        aria-label={`Your status: ${label}`}
        aria-haspopup="true"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
      >
        <span className="avatar avatar-agent">{initials(name)}</span>
        {status && <span className={`presence-dot ${status === "ONLINE" ? "is-online" : ""}`} />}
      </button>
      {open && (
        <div className="menu-list presence-list" role="menu">
          {CHOICES.map((c) => (
            <button
              key={c.status}
              type="button"
              role="menuitemradio"
              aria-checked={status === c.status}
              onClick={() => {
                onChange(c.status);
                setOpen(false);
              }}
            >
              <span className={`presence-dot ${c.status === "ONLINE" ? "is-online" : ""}`} />
              <span>
                <strong>{c.label}</strong>
                <small>{c.help}</small>
              </span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
