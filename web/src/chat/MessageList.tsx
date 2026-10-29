import { useEffect, useRef } from "react";
import type { Message, Option } from "../lib/api";

interface Props {
  messages: Message[];
  options: Option[];
  onOption: (option: Option) => void;
  disabled: boolean;
}

export function MessageList({ messages, options, onOption, disabled }: Props) {
  const end = useRef<HTMLDivElement>(null);

  useEffect(() => {
    end.current?.scrollIntoView?.({ block: "end" });
  }, [messages.length]);

  return (
    <div className="messages" role="log" aria-live="polite">
      {messages.map((m) => (
        <div key={m.id} className={`message message-${m.senderType.toLowerCase()}`}>
          {m.senderType === "AGENT" && m.senderName && <span className="sender">{m.senderName}</span>}
          {/* Rendered as text, never as HTML. */}
          <p>{m.body}</p>
        </div>
      ))}
      {options.length > 0 && (
        <div className="options">
          {options.map((o) => (
            <button key={o.id} type="button" onClick={() => onOption(o)} disabled={disabled}>
              {o.title}
            </button>
          ))}
        </div>
      )}
      <div ref={end} />
    </div>
  );
}
