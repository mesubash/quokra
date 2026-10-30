import { useEffect, useRef } from "react";
import type { Message, Option } from "../lib/api";
import { RobotAvatar } from "./RobotAvatar";
import type { PendingMessage } from "./useChat";

// The console mixes agent-only notes into the thread.
export type TimelineItem = Omit<Message, "senderType"> & { senderType: Message["senderType"] | "NOTE" };

interface Props {
  messages: TimelineItem[];
  options: Option[];
  onOption: (option: Option) => void;
  disabled: boolean;
  botName: string;
  typing?: boolean;
  pending?: PendingMessage[];
  onRetry?: (clientMessageId: string) => void;
  // Whose messages go on the right: the customer in the chat panel, the agent in the console.
  mine?: Message["senderType"];
  customerName?: string;
}

interface Group {
  key: string;
  sender: TimelineItem["senderType"];
  name: string | null;
  messages: TimelineItem[];
}

// Consecutive messages from the same sender are shown together, under one name and avatar.
function groupMessages(messages: TimelineItem[]): Group[] {
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

// Rendered as text, never as HTML. The time sits inside the bubble, bottom right.
function Bubble({ message }: { message: TimelineItem }) {
  return (
    <p className="bubble">
      <span className="bubble-text">{message.body}</span>
      <time className="bubble-time" dateTime={message.createdAt}>
        {time(message.createdAt)}
      </time>
    </p>
  );
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
  mine = "CUSTOMER",
  customerName = "Customer",
}: Props) {
  const end = useRef<HTMLDivElement>(null);
  const last = messages[messages.length - 1];
  // "Sent" stays under the customer's latest message until someone replies.
  const sentId = pending.length === 0 && last?.senderType === mine ? last.id : null;

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
        if (group.sender === "NOTE") {
          return (
            <div key={group.key} className="group group-note">
              <div className="group-body">
                <span className="sender">Private note · {group.name}</span>
                {group.messages.map((m) => (
                  <Bubble key={m.id} message={m} />
                ))}
              </div>
            </div>
          );
        }
        const isMine = group.sender === mine;
        const name = group.sender === "BOT" ? botName : group.sender === "CUSTOMER" ? customerName : group.name;
        return (
          <div key={group.key} className={`group ${isMine ? "group-mine" : "group-theirs"}`}>
            {!isMine && (
              <div className="group-avatar">
                {group.sender === "BOT" ? (
                  <RobotAvatar size={32} />
                ) : (
                  <span className={`avatar avatar-${group.sender.toLowerCase()}`}>{initials(name)}</span>
                )}
              </div>
            )}
            <div className="group-body">
              {!isMine && <span className="sender">{name}</span>}
              {group.messages.map((m) => (
                <Bubble key={m.id} message={m} />
              ))}
              {sentId && group.messages.some((m) => m.id === sentId) && (
                <span className="delivery">Sent</span>
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
