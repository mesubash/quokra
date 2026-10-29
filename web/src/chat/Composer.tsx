import { useEffect, useRef, useState, type FormEvent } from "react";

const MAX_LENGTH = 2000;

interface Props {
  onSend: (text: string) => Promise<void>;
  disabled: boolean;
  // Focus the input whenever this becomes true (e.g. the panel was just opened).
  focus?: boolean;
}

export function Composer({ onSend, disabled, focus = false }: Props) {
  const input = useRef<HTMLInputElement>(null);
  const [text, setText] = useState("");

  useEffect(() => {
    if (focus && !disabled) input.current?.focus();
  }, [focus, disabled]);
  const [sending, setSending] = useState(false);
  const [failed, setFailed] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
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
    }
  }

  return (
    <form className="composer" onSubmit={submit}>
      {failed && <p className="composer-error">Couldn't send. Try again.</p>}
      <div className="composer-row">
        <input
          ref={input}
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder="Type your message"
          aria-label="Message"
          maxLength={MAX_LENGTH}
          disabled={disabled}
        />
        <button type="submit" disabled={disabled || sending || !text.trim()}>
          Send
        </button>
      </div>
    </form>
  );
}
