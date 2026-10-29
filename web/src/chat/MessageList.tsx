import { useEffect, useRef } from "react";
import type { Message, Option } from "../lib/api";
import { RobotAvatar } from "./RobotAvatar";
import type { PendingMessage } from "./useChat";

interface Props {
  messages: Message[];
  options: Option[];
  onOption: (option: Option) => void;
  disabled: boolean;
  botName: string;
  typing?: boolean;
  pending?: PendingMessage[];
  onRetry?: (clientMessageId: string) => void;
}

interface Group {
  key: string;
  sender: Message["senderType"];
  name: string | null;
  messages: Message[];
}

// Consecutive messages from the same sender are shown together, under one name and avatar.
function groupMessages(messages: Message[]): Group[] {
  const groups: Group[] = [];
  for (const m of messages) {
    const last = groups[groups.length - 1];
    if (last && m.senderType !== "SYSTEM" && last.sender === m.senderType && last.name === m.senderName) {
      last.messages.push(m);
    } else {
      groups.push({ key: m.id, sender: m.senderType, name: m.senderName, messages: [m] });
    }
  }
  return groups;
}

function time(iso: string) {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "" : d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

function initials(name: string | null) {
  return (name ?? "?").split(/\s+/).map((p) => p[0]).join("").slice(0, 2).toUpperCase();
}

export function MessageList({
  messages,
  options,
  onOption,
  disabled,
  botName,
  typing = false,
  pending = [],
  onRetry,
}: Props) {
  const end = useRef<HTMLDivElement>(null);
  const last = messages[messages.length - 1];
  // "Sent" stays under the customer's latest message until someone replies.
  const sentId = pending.length === 0 && last?.senderType === "CUSTOMER" ? last.id : null;

  useEffect(() => {
    end.current?.scrollIntoView?.({ block: "end", behavior: "smooth" });
  }, [messages.length, typing, options.length, pending.length]);

  return (
    <div className="messages" role="log" aria-live="polite">
      {groupMessages(messages).map((group) => {
        if (group.sender === "SYSTEM") {
          return (
            <p key={group.key} className="system-note">
              {group.messages[0].body}
            </p>
          );
        }
        const mine = group.sender === "CUSTOMER";
        const name = group.sender === "AGENT" ? group.name : botName;
        return (
          <div key={group.key} className={`group ${mine ? "group-mine" : "group-theirs"}`}>
            {!mine && (
              <div className="group-avatar">
                {group.sender === "BOT" ? <RobotAvatar size={32} /> : <span className="avatar avatar-agent">{initials(group.name)}</span>}
              </div>
            )}
            <div className="group-body">
              {!mine && <span className="sender">{name}</span>}
              {group.messages.map((m) => (
                // Rendered as text, never as HTML.
                <p key={m.id} className="bubble" title={time(m.createdAt)}>
                  {m.body}
                </p>
              ))}
              {sentId && group.messages.some((m) => m.id === sentId) && (
                <span className="delivery">Sent · {time(group.messages[group.messages.length - 1].createdAt)}</span>
              )}
            </div>
          </div>
        );
      })}

      {pending.length > 0 && (
        <div className="group group-mine">
          <div className="group-body">
            {pending.map((p) => (
              <div key={p.clientMessageId} className={`pending pending-${p.status}`}>
                <p className="bubble">{p.body}</p>
                {p.status === "sending" ? (
                  <span className="delivery">Sending…</span>
                ) : (
                  <span className="delivery delivery-failed">
                    Not sent ·{" "}
                    <button type="button" onClick={() => onRetry?.(p.clientMessageId)}>
                      Retry
                    </button>
                  </span>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {typing && (
        <div className="group group-theirs">
          <div className="group-avatar">
            <RobotAvatar size={32} />
          </div>
          <div className="group-body">
            <p className="bubble typing" aria-label={`${botName} is typing`}>
              <span />
              <span />
              <span />
            </p>
          </div>
        </div>
      )}

      {options.length > 0 && !typing && (
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
