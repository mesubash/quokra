import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { IMAGE_TYPES } from "../lib/api";
import { PaperclipIcon, SendIcon } from "../lib/icons";

const MAX_LENGTH = 2000;
const MAX_HEIGHT = 120;

interface Props {
  // The message shows in the chat straight away, so the input clears at once.
  onSend: (text: string) => void;
  disabled: boolean;
  placeholder?: string;
  // Focus the input whenever this becomes true (e.g. the panel was just opened).
  focus?: boolean;
  // What the bot just asked for, so phones show the right keyboard and browsers can autofill.
  asking?: "EMAIL" | "NAME" | null;
  // Agent-only note: styled differently so it's never mistaken for a reply.
  note?: boolean;
  // Saved replies; typing "/" lists them, picking one puts its text in the input.
  replies?: { title: string; body: string }[];
  // Shows the 📎 button; called with the picked image.
  onAttach?: (file: File) => void;
}

// Replies whose title matches what's typed after "/". Only while the input is a single "/word".
export function matchReplies<T extends { title: string }>(text: string, replies: T[]): T[] {
  if (!text.startsWith("/") || text.includes("\n")) return [];
  const q = text.slice(1).trim().toLowerCase();
  return replies.filter((r) => r.title.toLowerCase().includes(q));
}

export function Composer({
  onSend,
  disabled,
  placeholder = "Type your message…",
  focus = false,
  asking = null,
  note = false,
  replies = [],
  onAttach,
}: Props) {
  const input = useRef<HTMLTextAreaElement>(null);
  const picker = useRef<HTMLInputElement>(null);
  const [text, setText] = useState("");
  const [active, setActive] = useState(0);
  const [hidden, setHidden] = useState(false);
  const matches = hidden ? [] : matchReplies(text, replies);

  useEffect(() => {
    if (focus && !disabled) input.current?.focus();
  }, [focus, disabled]);

  // Grow with the text, up to a few lines.
  useEffect(() => {
    const el = input.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, MAX_HEIGHT)}px`;
  }, [text]);

  function send() {
    const body = text.trim();
    if (!body || disabled) return;
    onSend(body);
    setText("");
    input.current?.focus();
  }

  function submit(e: FormEvent) {
    e.preventDefault();
    send();
  }

  function pick(body: string) {
    setText(body);
    input.current?.focus();
  }

  function type(value: string) {
    setText(value);
    setActive(0);
    setHidden(false);
  }

  // Enter sends; Shift+Enter adds a new line. While replies are listed, arrows move and Enter or Tab picks.
  function onKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (matches.length > 0) {
      const move = { ArrowDown: 1, ArrowUp: -1 }[e.key];
      if (move) {
        e.preventDefault();
        setActive((active + move + matches.length) % matches.length);
        return;
      }
      if (e.key === "Enter" || e.key === "Tab") {
        e.preventDefault();
        pick(matches[Math.min(active, matches.length - 1)].body);
        return;
      }
      if (e.key === "Escape") {
        e.preventDefault();
        setHidden(true);
        return;
      }
    }
    if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      send();
    }
  }

  const canSend = !disabled && text.trim().length > 0;

  return (
    <form className={note ? "composer composer-note" : "composer"} onSubmit={submit}>
      {matches.length > 0 && (
        <ul className="saved-replies" role="listbox" aria-label="Saved replies">
          {matches.map((r, i) => (
            <li
              key={r.title + i}
              role="option"
              aria-selected={i === active}
              onMouseDown={(e) => {
                e.preventDefault();
                pick(r.body);
              }}
            >
              <strong>{r.title}</strong>
              <span className="muted">{r.body}</span>
            </li>
          ))}
        </ul>
      )}
      <div className="composer-row">
        {onAttach && (
          <>
            <button
              type="button"
              className="attach"
              aria-label="Send an image"
              title="Send an image"
              disabled={disabled}
              onClick={() => picker.current?.click()}
            >
              <PaperclipIcon size={18} />
            </button>
            <input
              ref={picker}
              type="file"
              accept={IMAGE_TYPES}
              hidden
              onChange={(e) => {
                const file = e.target.files?.[0];
                e.target.value = "";
                if (file) onAttach(file);
              }}
            />
          </>
        )}
        <textarea
          ref={input}
          rows={1}
          value={text}
          onChange={(e) => type(e.target.value)}
          onKeyDown={onKeyDown}
          placeholder={placeholder}
          aria-label={asking === "EMAIL" ? "Your email" : asking === "NAME" ? "Your name" : "Message"}
          inputMode={asking === "EMAIL" ? "email" : undefined}
          autoComplete={asking === "EMAIL" ? "email" : asking === "NAME" ? "name" : "off"}
          maxLength={MAX_LENGTH}
          disabled={disabled}
        />
        <button type="submit" className="send" disabled={!canSend} aria-label={note ? "Add note" : "Send"}>
          <SendIcon size={18} />
        </button>
      </div>
    </form>
  );
}
