import { useEffect, useRef, useState } from "react";
import { BellIcon } from "../lib/icons";
import type { NotifyPrefs } from "../lib/notifyPrefs";
import { chime } from "../lib/sound";

// What the dashboard (through sdk.js) says about browser notifications.
export interface HostNotifications {
  available: boolean;
  permission: string; // "default" | "granted" | "denied"
}

const EVENTS: { key: keyof NotifyPrefs; label: string }[] = [
  { key: "waiting", label: "New chat waiting" },
  { key: "myMessages", label: "Customer writes in my chat" },
  { key: "transfers", label: "Chat transferred to me" },
  { key: "notes", label: "Note on my chat" },
];

export function NotifyMenu({
  prefs,
  onChange,
  host,
  onTurnOn,
}: {
  prefs: NotifyPrefs;
  onChange: (prefs: NotifyPrefs) => void;
  host: HostNotifications | null;
  onTurnOn: () => void;
}) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onClick = (e: MouseEvent) => !root.current?.contains(e.target as Node) && setOpen(false);
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    document.addEventListener("mousedown", onClick);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onClick);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  const set = (key: keyof NotifyPrefs, value: boolean) => {
    onChange({ ...prefs, [key]: value });
    if (key === "sound" && value) chime("message");
  };
  const allOff = !prefs.sound && !prefs.browser;

  return (
    <div className="menu" ref={root}>
      <button
        type="button"
        className="icon-button quiet"
        aria-label="Notifications"
        aria-haspopup="true"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
      >
        <BellIcon size={18} off={allOff} />
      </button>
      {open && (
        <div className="menu-list notify-menu" role="group" aria-label="Notifications">
          <p className="notify-heading">Notify me about</p>
          {EVENTS.map((e) => (
            <Toggle key={e.key} label={e.label} checked={prefs[e.key]} onChange={(v) => set(e.key, v)} />
          ))}
          <p className="notify-heading">How</p>
          <Toggle label="Sound" checked={prefs.sound} onChange={(v) => set("sound", v)} />
          {host?.available ? (
            <>
              <Toggle
                label="Desktop notifications"
                checked={prefs.browser}
                onChange={(v) => set("browser", v)}
                disabled={host.permission === "denied"}
              />
              {prefs.browser && host.permission === "default" && (
                <button type="button" className="button button-small notify-turn-on" onClick={onTurnOn}>
                  Allow in this browser
                </button>
              )}
              {host.permission === "denied" && (
                <p className="notify-help muted">Blocked in this browser. Allow them in the site settings.</p>
              )}
            </>
          ) : (
            <p className="notify-help muted">Desktop notifications aren't turned on for this dashboard.</p>
          )}
        </div>
      )}
    </div>
  );
}

function Toggle({
  label,
  checked,
  onChange,
  disabled = false,
}: {
  label: string;
  checked: boolean;
  onChange: (value: boolean) => void;
  disabled?: boolean;
}) {
  return (
    <label className="notify-toggle">
      <span>{label}</span>
      <input
        type="checkbox"
        role="switch"
        checked={checked && !disabled}
        disabled={disabled}
        onChange={(e) => onChange(e.target.checked)}
      />
    </label>
  );
}
