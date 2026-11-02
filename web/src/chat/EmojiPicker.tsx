import { useEffect, useRef, useState } from "react";
import { SmileIcon } from "../lib/icons";

// A built-in set in tabs: no library, nothing loaded from elsewhere. The phone or computer emoji keyboard
// still has everything else. "Recent" remembers what this person picked last, in their browser.
export const GROUPS: { name: string; emojis: string[] }[] = [
  { name: "Smileys", emojis: "😀 😃 😄 😁 😆 😅 😂 🤣 🙂 😊 😇 🥰 😍 🤩 😘 😋 😛 😜 🤪 😎 🤓 🧐 🤔 🤨 😐 😑 😶 🙄 😏 😬 😌 😔 😪 😴 😷 🤒 🤕 🤢 🥵 🥶 😵 🤯 🥳 😕 😟 🙁 😮 😯 😲 😳 🥺 😢 😭 😱 😖 😣 😞 😓 😩 😫 😤 😡 😠".split(" ") },
  { name: "Gestures", emojis: "👍 👎 👌 🤌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✋ 🤚 🖐️ 🖖 👋 👏 🙌 👐 🤲 🤝 🙏 ✍️ 💪 🫶 ❤️ 🧡 💛 💚 💙 💜 🤍 💔 ❣️ 💯 💢 💥 💫 💬 👀 🧠 🫡 🤗 🤭 🤫".split(" ") },
  { name: "Travel", emojis: "✈️ 🛫 🛬 🚁 🚑 🚒 🚓 🚕 🚗 🚌 🚐 🛵 🚲 🚶 🧗 🏃 🧭 🗺️ 📍 🏔️ ⛰️ 🗻 🏕️ ⛺ 🏞️ 🏜️ 🏝️ 🏖️ 🏨 🏥 🏦 🏛️ 🛕 ⛩️ 🌄 🌅 🌍 🌏 ☀️ 🌤️ ⛅ 🌧️ ⛈️ 🌨️ ❄️ 🌬️ 🌫️ 🌈 🌙 ⭐".split(" ") },
  { name: "Objects", emojis: "🎒 🧳 📱 💻 ⌨️ 🖥️ 📷 🔋 🔌 💡 🔦 🧯 🩺 💊 🩹 🧪 🌡️ 📞 ☎️ 📧 📨 📩 📮 📦 📄 📃 📑 📊 📅 📆 🗓️ 📎 🖊️ ✏️ 📝 🔒 🔓 🔑 🗝️ 💳 💰 💵 🧾 🏷️ ⏰ ⏱️ 🕐 ⌛ ⏳ 🎁 🎉 🎊".split(" ") },
  { name: "Symbols", emojis: "✅ ☑️ ✔️ ❌ ❎ ⚠️ ❗ ❓ ❕ ❔ ‼️ ⁉️ 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🔺 🔻 ➡️ ⬅️ ⬆️ ⬇️ ↩️ ↪️ 🔄 🔁 ➕ ➖ ✖️ ➗ 🆗 🆕 🆘 🆙 ℹ️ 🚫 ⛔ 📵 🔞 ♻️ ✳️ ❇️ ⭕ 🔅 🔆".split(" ") },
];

const RECENT_KEY = "quokra.recent-emoji";
const RECENT_MAX = 16;

function loadRecent(): string[] {
  try {
    const saved = JSON.parse(localStorage.getItem(RECENT_KEY) ?? "[]") as unknown;
    return Array.isArray(saved) ? saved.filter((e): e is string => typeof e === "string").slice(0, RECENT_MAX) : [];
  } catch {
    return [];
  }
}

function remember(emoji: string, recent: string[]) {
  const next = [emoji, ...recent.filter((e) => e !== emoji)].slice(0, RECENT_MAX);
  try {
    localStorage.setItem(RECENT_KEY, JSON.stringify(next));
  } catch {
    // Storage blocked: recent ones last until the page reloads.
  }
  return next;
}

export function EmojiPicker({ onPick, disabled }: { onPick: (emoji: string) => void; disabled: boolean }) {
  const [open, setOpen] = useState(false);
  const [recent, setRecent] = useState(loadRecent);
  const [tab, setTab] = useState(() => (loadRecent().length > 0 ? "Recent" : GROUPS[0].name));
  const root = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onClick = (e: MouseEvent) => !root.current?.contains(e.target as Node) && setOpen(false);
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      e.stopPropagation();
      setOpen(false);
    };
    document.addEventListener("mousedown", onClick);
    document.addEventListener("keydown", onKey, true);
    return () => {
      document.removeEventListener("mousedown", onClick);
      document.removeEventListener("keydown", onKey, true);
    };
  }, [open]);

  const tabs = [...(recent.length > 0 ? [{ name: "Recent", emojis: recent }] : []), ...GROUPS];
  const shown = tabs.find((t) => t.name === tab) ?? tabs[0];

  return (
    <div className="emoji" ref={root}>
      <button
        type="button"
        className="attach"
        aria-label="Add an emoji"
        title="Add an emoji"
        aria-haspopup="dialog"
        aria-expanded={open}
        disabled={disabled}
        onClick={() => {
          // Opens on the person's recent ones when they have some.
          if (!open && recent.length > 0) setTab("Recent");
          setOpen(!open);
        }}
      >
        <SmileIcon size={18} />
      </button>
      {open && (
        <div className="emoji-panel" role="dialog" aria-label="Emoji">
          <div className="emoji-tabs" role="tablist" aria-label="Emoji groups">
            {tabs.map((t) => (
              <button
                key={t.name}
                type="button"
                role="tab"
                aria-selected={t.name === shown.name}
                title={t.name}
                aria-label={t.name}
                onClick={() => setTab(t.name)}
              >
                {t.name === "Recent" ? "🕘" : t.emojis[0]}
              </button>
            ))}
          </div>
          <p className="emoji-group-name">{shown.name}</p>
          <div className="emoji-grid" role="tabpanel" aria-label={shown.name}>
            {shown.emojis.map((e) => (
              <button
                key={e}
                type="button"
                aria-label={e}
                onClick={() => {
                  onPick(e);
                  setRecent((r) => remember(e, r));
                  setOpen(false);
                }}
              >
                {e}
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
