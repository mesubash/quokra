import { useEffect, useRef, useState } from "react";
import { BellIcon } from "../lib/icons";
import type { NotifyPrefs } from "../lib/notifyPrefs";
import { chime } from "../lib/sound";

// What the dashboard (through sdk.js) says about browser notifications.
export interface HostNotifications {
  available: boolean;
  permission: string; // "default" | "granted" | "denied"
}

const EVENTS: { key: keyof NotifyPrefs; label: string; help: string }[] = [
  { key: "waiting", label: "New chat waiting", help: "Someone asked for a person and nobody has the chat yet." },
  { key: "myMessages", label: "Customer writes in my chat", help: "A new message in a chat you're handling." },
  { key: "transfers", label: "Chat transferred to me", help: "A teammate hands a chat over to you." },
  { key: "notes", label: "Note on my chat", help: "A teammate leaves a private note on your chat." },
];

export function NotifyMenu({ prefs, onChange, host, onTurnOn }: NotifyOptionsProps) {
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
          <NotifyOptions prefs={prefs} onChange={onChange} host={host} onTurnOn={onTurnOn} />
        </div>
      )}
    </div>
  );
}

export interface NotifyOptionsProps {
  prefs: NotifyPrefs;
  onChange: (prefs: NotifyPrefs) => void;
  host: HostNotifications | null;
  onTurnOn: () => void;
  // In Settings each switch gets a line saying what it does.
  explained?: boolean;
}

// The switches, in the bell menu and in Settings → Notifications.
export function NotifyOptions({ prefs, onChange, host, onTurnOn, explained = false }: NotifyOptionsProps) {
  const set = (key: keyof NotifyPrefs, value: boolean) => {
    onChange({ ...prefs, [key]: value });
    if (key === "sound" && value) chime("message");
  };

  return (
    <>
      <p className="notify-heading">Notify me about</p>
      {EVENTS.map((e) => (
        <Toggle
          key={e.key}
          label={e.label}
          help={explained ? e.help : undefined}
          checked={prefs[e.key]}
          onChange={(v) => set(e.key, v)}
        />
      ))}
      <p className="notify-heading">How</p>
      <Toggle
        label="Sound"
        help={explained ? "A short chime. Browsers play it after your first click on the page." : undefined}
        checked={prefs.sound}
        onChange={(v) => set("sound", v)}
      />
      {host?.available ? (
        <>
          <Toggle
            label="Desktop notifications"
            help={explained ? "Pop up while the dashboard is in another tab or window." : undefined}
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
    </>
  );
}

function Toggle({
  label,
  help,
  checked,
  onChange,
  disabled = false,
}: {
  label: string;
  help?: string;
  checked: boolean;
  onChange: (value: boolean) => void;
  disabled?: boolean;
}) {
  return (
    <label className="notify-toggle">
      <span>
        {label}
        {help && <small>{help}</small>}
      </span>
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
