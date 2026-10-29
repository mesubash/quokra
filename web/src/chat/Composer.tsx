import { useState, type FormEvent } from "react";

const MAX_LENGTH = 2000;

export function Composer({ onSend, disabled }: { onSend: (text: string) => Promise<void>; disabled: boolean }) {
  const [text, setText] = useState("");
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
