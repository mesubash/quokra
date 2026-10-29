import { useEffect, useState } from "react";
import { embedded, onHostMessage, postToHost } from "../lib/bridge";
import { widgetApi, type Conversation, type Tenant } from "../lib/api";
import { Composer } from "./Composer";
import { MessageList } from "./MessageList";
import { activeOptions, countUnread } from "./messages";
import { getSession } from "./session";
import { useChat } from "./useChat";

// Bubble and popup panels start hidden and are opened by sdk.js; inline and full-page ones are always shown.
const mode = new URLSearchParams(window.location.search).get("mode") ?? "full";
const startsHidden = embedded && (mode === "bubble" || mode === "popup");

type Setup =
  | { status: "loading" }
  | { status: "ready"; tenant: Tenant; token: string }
  | { status: "error"; message: string };

export function ChatPanel({ slug }: { slug: string }) {
  const pk = new URLSearchParams(window.location.search).get("pk");
  const [setup, setSetup] = useState<Setup>({ status: "loading" });
  const [visible, setVisible] = useState(!startsHidden);

  // Listen before telling the host we're ready, so its "open" reply isn't missed.
  useEffect(() => onHostMessage((m) => setVisible(m.type === "chat.open")), []);

  useEffect(() => {
    if (!pk) {
      setSetup({ status: "error", message: "Missing publishable key." });
      return;
    }
    let cancelled = false;
    Promise.all([widgetApi.tenant(pk), getSession(pk)])
      .then(([tenant, session]) => {
        if (cancelled) return;
        if (tenant.slug !== slug) {
          setSetup({ status: "error", message: "This chat isn't set up for this page." });
          return;
        }
        if (tenant.branding.primaryColor) {
          document.documentElement.style.setProperty("--primary", tenant.branding.primaryColor);
        }
        setSetup({ status: "ready", tenant, token: session.sessionToken });
        postToHost({ type: "chat.ready", color: tenant.branding.primaryColor });
      })
      .catch(() => !cancelled && setSetup({ status: "error", message: "Chat is unavailable right now." }));
    return () => {
      cancelled = true;
    };
  }, [pk, slug]);

  if (setup.status === "loading") return <p className="panel muted">Loading…</p>;
  if (setup.status === "error") return <p className="panel muted">{setup.message}</p>;
  return <Chat tenant={setup.tenant} token={setup.token} visible={visible} />;
}

function Chat({ tenant, token, visible }: { tenant: Tenant; token: string; visible: boolean }) {
  const chat = useChat(token);
  const options = chat.conversation?.status === "BOT" ? activeOptions(chat.messages) : [];
  const [seenUpTo, setSeenUpTo] = useState<string | null>(null);
  const lastId = chat.messages[chat.messages.length - 1]?.id ?? null;
  const unread = visible ? 0 : countUnread(chat.messages, seenUpTo);

  // History loaded on start counts as seen; after that, only what arrives while visible.
  useEffect(() => {
    if (lastId && (visible || seenUpTo === null)) setSeenUpTo(lastId);
  }, [lastId, visible, seenUpTo]);

  useEffect(() => postToHost({ type: "chat.unread", count: unread }), [unread]);

  return (
    <main className="chat">
      <header className="panel-header">
        {tenant.branding.logoUrl && <img src={tenant.branding.logoUrl} alt="" height={24} />}
        <h1>{tenant.displayName}</h1>
        {startsHidden && (
          <button type="button" className="close" aria-label="Close chat" onClick={() => postToHost({ type: "chat.close" })}>
            ×
          </button>
        )}
      </header>
      {chat.error ? (
        <p className="panel muted">{chat.error}</p>
      ) : (
        <>
          <StatusLine conversation={chat.conversation} online={chat.online} />
          <MessageList
            messages={chat.messages}
            options={options}
            onOption={(o) => void chat.send({ optionId: o.id }).catch(() => {})}
            disabled={!chat.conversation}
          />
          <Composer onSend={(body) => chat.send({ body })} disabled={!chat.conversation} />
        </>
      )}
    </main>
  );
}

function StatusLine({ conversation, online }: { conversation: Conversation | null; online: boolean }) {
  if (conversation && !online) return <p className="status">Reconnecting…</p>;
  if (conversation?.status === "WAITING") return <p className="status">Waiting for someone from our team…</p>;
  if (conversation?.status === "HUMAN" && conversation.assignee) {
    return <p className="status">You're chatting with {conversation.assignee.name}</p>;
  }
  if (conversation?.status === "RESOLVED") return <p className="status">This conversation is closed. Write again any time.</p>;
  return null;
}
