import { Modal } from "./ConversationView";

export type Shortcut = "next" | "prev" | "close" | "accept" | "reply" | "help" | "leave";

// Menus, popovers and dialogs handle their own keys while they're open.
const OVERLAY = "dialog[open], [role='alertdialog'], [aria-haspopup][aria-expanded='true']";
const TYPING = "input, textarea, select, [contenteditable]:not([contenteditable='false'])";

// The console shortcut for a key press, if it is one. Escape in a text field only leaves the field.
export function shortcutFor(e: KeyboardEvent, agent: boolean): Shortcut | null {
  if (e.defaultPrevented || e.ctrlKey || e.metaKey || e.altKey) return null;
  if (document.querySelector(OVERLAY)) return null;
  if (e.target instanceof Element && e.target.closest(TYPING)) return e.key === "Escape" ? "leave" : null;
  switch (e.key) {
    case "ArrowDown":
    case "j":
      return "next";
    case "ArrowUp":
    case "k":
      return "prev";
    case "Escape":
      return "close";
    case "?":
      return "help";
    case "a":
      return agent ? "accept" : null;
    case "r":
      return agent ? "reply" : null;
    default:
      return null;
  }
}

// The conversation one step up or down the list; with none open, the first (or last) one.
export function stepThrough(ids: string[], selected: string | null, step: 1 | -1) {
  if (ids.length === 0) return null;
  const at = selected ? ids.indexOf(selected) : -1;
  if (at === -1) return step > 0 ? ids[0] : ids[ids.length - 1];
  return ids[Math.min(ids.length - 1, Math.max(0, at + step))];
}

export function ShortcutsHelp({ agent, onClose }: { agent: boolean; onClose: () => void }) {
  const keys: [string[], string][] = [
    [["↓", "j"], "Next conversation"],
    [["↑", "k"], "Previous conversation"],
    [["Esc"], "Close the conversation"],
    ...(agent
      ? ([
          [["a"], "Accept a waiting chat"],
          [["r"], "Write a reply"],
        ] as [string[], string][])
      : []),
    [["?"], "Show these shortcuts"],
  ];
  return (
    <Modal title="Keyboard shortcuts" onClose={onClose}>
      <dl className="shortcuts">
        {keys.map(([k, label]) => (
          <div key={label}>
            <dt>
              {k.map((key) => (
                <kbd key={key}>{key}</kbd>
              ))}
            </dt>
            <dd>{label}</dd>
          </div>
        ))}
      </dl>
      <div className="modal-actions">
        <button type="button" className="button button-quiet" onClick={onClose}>
          Close
        </button>
      </div>
    </Modal>
  );
}
