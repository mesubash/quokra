import { Fragment, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import type { Message, Option } from "../lib/api";
import { CloseIcon } from "../lib/icons";
import { RobotAvatar } from "./RobotAvatar";
import { dayKey, dayLabel } from "./messages";
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
  // Fetches an image message's file (with the token the page already has).
  loadImage?: (messageId: string) => Promise<Blob>;
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
function Bubble({ message, loadImage }: { message: TimelineItem; loadImage?: Props["loadImage"] }) {
  if (message.attachment && loadImage) return <ImageBubble message={message} load={loadImage} />;
  return (
    <p className="bubble">
      <span className="bubble-text">{message.body}</span>
      <time className="bubble-time" dateTime={message.createdAt}>
        {time(message.createdAt)}
      </time>
    </p>
  );
}

// Click shows the image full size over the chat; Escape, ✕ or a click outside closes it.
function ImageBubble({ message, load }: { message: TimelineItem; load: (messageId: string) => Promise<Blob> }) {
  const [url, setUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (!open) return;
    const close = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    document.addEventListener("keydown", close);
    return () => document.removeEventListener("keydown", close);
  }, [open]);

  useEffect(() => {
    let live = true;
    let made: string | null = null;
    load(message.id)
      .then((blob) => {
        if (!live) return;
        made = URL.createObjectURL(blob);
        setUrl(made);
      })
      .catch(() => live && setFailed(true));
    return () => {
      live = false;
      if (made) URL.revokeObjectURL(made);
    };
  }, [message.id, load]);

  return (
    <p className="bubble bubble-image">
      {url ? (
        <button type="button" className="image-open" onClick={() => setOpen(true)} aria-label="Show full size">
          <img src={url} alt={message.attachment?.name ?? "Image"} />
        </button>
      ) : (
        <span className="bubble-text">{failed ? "The image couldn't load." : "Loading image…"}</span>
      )}
      <time className="bubble-time" dateTime={message.createdAt}>
        {time(message.createdAt)}
      </time>
      {open &&
        url &&
        // On <body>, so nothing in the thread (scrolling, animations) can box it in.
        createPortal(
          <div className="lightbox" role="dialog" aria-modal="true" aria-label="Image" onClick={() => setOpen(false)}>
            <img src={url} alt={message.attachment?.name ?? "Image"} onClick={(e) => e.stopPropagation()} />
            <button type="button" className="lightbox-close" aria-label="Close" onClick={() => setOpen(false)}>
              <CloseIcon size={22} />
            </button>
          </div>,
          document.body,
        )}
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
  loadImage,
}: Props) {
  const end = useRef<HTMLDivElement>(null);
  const last = messages[messages.length - 1];
  // "Sent" stays under the customer's latest message until someone replies.
  const sentId = pending.length === 0 && last?.senderType === mine ? last.id : null;

  useEffect(() => {
    end.current?.scrollIntoView?.({ block: "end", behavior: "smooth" });
  }, [messages.length, typing, options.length, pending.length]);

  const groups = groupMessages(messages);
  const renderGroup = (group: Group) => {
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
              <Bubble key={m.id} message={m} loadImage={loadImage} />
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
            <Bubble key={m.id} message={m} loadImage={loadImage} />
          ))}
          {sentId && group.messages.some((m) => m.id === sentId) && (
            <span className="delivery">Sent</span>
          )}
        </div>
      </div>
    );
  };

  return (
    <div className="messages" role="log" aria-live="polite">
      {groups.map((group, i) => (
        <Fragment key={group.key}>
          {(i === 0 || dayKey(group.messages[0].createdAt) !== dayKey(groups[i - 1].messages[0].createdAt)) && (
            <p className="day-divider" role="separator">
              <span>{dayLabel(group.messages[0].createdAt)}</span>
            </p>
          )}
          {renderGroup(group)}
        </Fragment>
      ))}

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
