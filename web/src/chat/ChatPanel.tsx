import { useCallback, useEffect, useRef, useState } from "react";
import { embedded, onHostMessage, postToHost } from "../lib/bridge";
import { textOn } from "../lib/color";
import { CloseIcon } from "../lib/icons";
import { widgetApi, type Conversation, type Tenant } from "../lib/api";
import { Composer } from "./Composer";
import { MessageList } from "./MessageList";
import { RobotAvatar } from "./RobotAvatar";
import { activeOptions, countUnread } from "./messages";
import { forgetSession, getSession } from "./session";
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
  const renewed = useRef(false);

  // Listen before telling the host we're ready, so its "open" reply isn't missed.
  useEffect(() => onHostMessage((m) => setVisible(m.type === "chat.open")), []);

  // Escape closes a bubble or popup panel, like any dialog.
  useEffect(() => {
    if (!startsHidden) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && postToHost({ type: "chat.close" });
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

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
          document.documentElement.style.setProperty("--on-primary", textOn(tenant.branding.primaryColor));
        }
        setSetup({ status: "ready", tenant, token: session.sessionToken });
        postToHost({ type: "chat.ready", color: tenant.branding.primaryColor, teaser: tenant.teaser });
      })
      .catch(() => !cancelled && setSetup({ status: "error", message: "Chat is unavailable right now." }));
    return () => {
      cancelled = true;
    };
  }, [pk, slug]);

  // A stored session can stop working (expired, or the server's key changed): start a new one, once.
  const renewSession = useCallback(() => {
    if (!pk || renewed.current) {
      setSetup({ status: "error", message: "Chat is unavailable right now." });
      return;
    }
    renewed.current = true;
    forgetSession(pk);
    getSession(pk)
      .then((session) => setSetup((s) => (s.status === "ready" ? { ...s, token: session.sessionToken } : s)))
      .catch(() => setSetup({ status: "error", message: "Chat is unavailable right now." }));
  }, [pk]);

  if (setup.status === "loading") return <p className="panel muted">Loading…</p>;
  if (setup.status === "error") return <p className="panel muted">{setup.message}</p>;
  return (
    <Chat key={setup.token} tenant={setup.tenant} token={setup.token} visible={visible} onUnauthorized={renewSession} />
  );
}

interface ChatProps {
  tenant: Tenant;
  token: string;
  visible: boolean;
  onUnauthorized: () => void;
}

function Chat({ tenant, token, visible, onUnauthorized }: ChatProps) {
  const chat = useChat(token, onUnauthorized);
  const status = chat.conversation?.status;
  const options = status === "BOT" ? activeOptions(chat.messages) : [];
  const [seenUpTo, setSeenUpTo] = useState<string | null>(null);
  const [awaitingBot, setAwaitingBot] = useState(false);
  const last = chat.messages[chat.messages.length - 1];
  const lastId = last?.id ?? null;
  const unread = visible ? 0 : countUnread(chat.messages, seenUpTo);
  const typing = awaitingBot && status === "BOT" && last?.senderType === "CUSTOMER";

  // History loaded on start counts as seen; after that, only what arrives while visible.
  useEffect(() => {
    if (lastId && (visible || seenUpTo === null)) setSeenUpTo(lastId);
  }, [lastId, visible, seenUpTo]);

  useEffect(() => postToHost({ type: "chat.unread", count: unread }), [unread]);

  // The typing dots never hang around if a reply doesn't come.
  useEffect(() => {
    if (!awaitingBot) return;
    const timer = setTimeout(() => setAwaitingBot(false), 6000);
    return () => clearTimeout(timer);
  }, [awaitingBot, lastId]);

  async function send(message: { body?: string; optionId?: string }) {
    setAwaitingBot(true);
    try {
      await chat.send(message);
    } catch (e) {
      setAwaitingBot(false);
      throw e;
    }
  }

  return (
    <main className="chat">
      <header className="panel-header">
        <span className="header-avatar">
          {tenant.branding.logoUrl ? <img src={tenant.branding.logoUrl} alt="" /> : <RobotAvatar size={40} wave={visible} />}
        </span>
        <div className="header-text">
          <h1>{tenant.displayName}</h1>
          <HeaderStatus conversation={chat.conversation} online={chat.online} />
        </div>
        {startsHidden && (
          <button type="button" className="icon-button" aria-label="Close chat" onClick={() => postToHost({ type: "chat.close" })}>
            <CloseIcon size={22} />
          </button>
        )}
      </header>
      {chat.error ? (
        <p className="panel muted">{chat.error}</p>
      ) : (
        <>
          <MessageList
            messages={chat.messages}
            options={options}
            onOption={(o) => void send({ optionId: o.id }).catch(() => {})}
            disabled={!chat.conversation}
            botName={tenant.displayName}
            typing={typing}
          />
          <Composer
            onSend={(body) => send({ body })}
            disabled={!chat.conversation}
            focus={visible}
            placeholder={status === "HUMAN" && chat.conversation?.assignee ? `Reply to ${chat.conversation.assignee.name}…` : `Reply to ${tenant.displayName}…`}
          />
        </>
      )}
    </main>
  );
}

function HeaderStatus({ conversation, online }: { conversation: Conversation | null; online: boolean }) {
  if (conversation && !online) return <p className="header-status">Reconnecting…</p>;
  switch (conversation?.status) {
    case "WAITING":
      return <p className="header-status"><span className="dot dot-waiting" />Connecting you with our team…</p>;
    case "HUMAN":
      return (
        <p className="header-status">
          <span className="dot" />
          {conversation.assignee ? `Chatting with ${conversation.assignee.name}` : "Chatting with our team"}
        </p>
      );
    case "RESOLVED":
      return <p className="header-status">Conversation closed · write again any time</p>;
    default:
      return <p className="header-status"><span className="dot" />Online · replies instantly</p>;
  }
}
