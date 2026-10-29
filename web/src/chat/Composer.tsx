import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { SendIcon } from "../lib/icons";

const MAX_LENGTH = 2000;
const MAX_HEIGHT = 120;

interface Props {
  onSend: (text: string) => Promise<void>;
  disabled: boolean;
  placeholder?: string;
  // Focus the input whenever this becomes true (e.g. the panel was just opened).
  focus?: boolean;
}

export function Composer({ onSend, disabled, placeholder = "Type your message…", focus = false }: Props) {
  const input = useRef<HTMLTextAreaElement>(null);
  const [text, setText] = useState("");
  const [sending, setSending] = useState(false);
  const [failed, setFailed] = useState(false);

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

  async function send() {
    const body = text.trim();
    if (!body || sending) return;
    setSending(true);
    setFailed(false);
    try {
      await onSend(body);
      setText("");
    } catch {
      setFailed(true);
    } finally {
      setSending(false);
      input.current?.focus();
    }
  }

  function submit(e: FormEvent) {
    e.preventDefault();
    void send();
  }

  // Enter sends; Shift+Enter adds a new line.
  function onKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      void send();
    }
  }

  const canSend = !disabled && !sending && text.trim().length > 0;

  return (
    <form className="composer" onSubmit={submit}>
      {failed && <p className="composer-error">Couldn't send. Try again.</p>}
      <div className="composer-row">
        <textarea
          ref={input}
          rows={1}
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={onKeyDown}
          placeholder={placeholder}
          aria-label="Message"
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
