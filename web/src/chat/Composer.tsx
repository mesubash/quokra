import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { SendIcon } from "../lib/icons";

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
}

export function Composer({ onSend, disabled, placeholder = "Type your message…", focus = false, asking = null }: Props) {
  const input = useRef<HTMLTextAreaElement>(null);
  const [text, setText] = useState("");

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

  // Enter sends; Shift+Enter adds a new line.
  function onKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      send();
    }
  }

  const canSend = !disabled && text.trim().length > 0;

  return (
    <form className="composer" onSubmit={submit}>
      <div className="composer-row">
        <textarea
          ref={input}
          rows={1}
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={onKeyDown}
          placeholder={placeholder}
          aria-label={asking === "EMAIL" ? "Your email" : asking === "NAME" ? "Your name" : "Message"}
          inputMode={asking === "EMAIL" ? "email" : undefined}
          autoComplete={asking === "EMAIL" ? "email" : asking === "NAME" ? "name" : "off"}
          maxLength={MAX_LENGTH}
          disabled={disabled}
        />
        <button type="submit" className="send" disabled={!canSend} aria-label="Send">
          <SendIcon size={18} />
        </button>
      </div>
    </form>
  );
}
