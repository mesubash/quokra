import { useCallback, useEffect, useRef, useState } from "react";
import { embedded, onHostMessage, postToHost } from "../lib/bridge";
import { accent, applyColor } from "../lib/theme";
import { CloseIcon } from "../lib/icons";
import { ApiError, widgetApi, type Conversation, type Tenant } from "../lib/api";
import { Composer } from "./Composer";
import { MessageList } from "./MessageList";
import { RobotAvatar } from "./RobotAvatar";
import { activeOptions, countUnread } from "./messages";
import { forgetSession, getSession, saveSession } from "./session";
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
  const [attempt, setAttempt] = useState(0);
  const renewed = useRef(false);

  // The host's identity token: swap this guest session for the logged-in customer's.
  // The new token remounts the chat, which then loads the customer's conversation.
  const setupRef = useRef(setup);
  setupRef.current = setup;
  const identify = useCallback(
    (identityToken: string) => {
      const current = setupRef.current;
      if (!pk || current.status !== "ready") return;
      widgetApi
        .identify(current.token, identityToken)
        .then((session) => {
          saveSession(pk, session);
          setSetup((s) => (s.status === "ready" ? { ...s, token: session.sessionToken } : s));
          postToHost({ type: "chat.identified" });
        })
        .catch((e) => postToHost({ type: "chat.identify.failed", code: e instanceof ApiError ? e.code : "UNKNOWN" }));
    },
    [pk],
  );

  // The customer logged out on the host site: forget their session and carry on as a new guest,
  // so the next person on this browser doesn't see their chat.
  const logout = useCallback(() => {
    if (!pk) return;
    forgetSession(pk);
    renewed.current = false;
    getSession(pk)
      .then((session) => setSetup((s) => (s.status === "ready" ? { ...s, token: session.sessionToken } : s)))
      .catch(() => setSetup({ status: "error", message: "We can't connect right now. Please try again in a moment." }));
  }, [pk]);

  // Listen before telling the host we're ready, so its "open" reply isn't missed.
  useEffect(
    () =>
      onHostMessage((m) => {
        if (m.type === "chat.open" || m.type === "chat.close") setVisible(m.type === "chat.open");
        else if (m.type === "chat.identify") identify(m.token);
        else if (m.type === "chat.logout") logout();
      }),
    [identify, logout],
  );

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
        // The host's accent wins over the tenant's colour.
        const color = accent ?? tenant.branding.primaryColor;
        if (color) applyColor(color);
        setSetup({ status: "ready", tenant, token: session.sessionToken });
        postToHost({ type: "chat.ready", color, teaser: tenant.teaser });
      })
      .catch(() => !cancelled && setSetup({ status: "error", message: "We can't connect right now. Please try again in a moment." }));
    return () => {
      cancelled = true;
    };
  }, [pk, slug, attempt]);

  // A stored session can stop working (expired, or the server's key changed): start a new one, once.
  const renewSession = useCallback(() => {
    if (!pk || renewed.current) {
      setSetup({ status: "error", message: "We can't connect right now. Please try again in a moment." });
      return;
    }
    renewed.current = true;
    forgetSession(pk);
    getSession(pk)
      .then((session) => setSetup((s) => (s.status === "ready" ? { ...s, token: session.sessionToken } : s)))
      .catch(() => setSetup({ status: "error", message: "We can't connect right now. Please try again in a moment." }));
  }, [pk]);

  if (setup.status === "loading") return <PanelState title="Chat" message="Loading…" />;
  if (setup.status === "error") {
    const retry = () => {
      renewed.current = false;
      setSetup({ status: "loading" });
      setAttempt((n) => n + 1);
    };
    return <PanelState title="Chat" message={setup.message} onRetry={pk ? retry : undefined} />;
  }
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

  async function send(message: { body: string; optionId?: string }) {
    setAwaitingBot(true);
    if (!(await chat.send(message))) setAwaitingBot(false);
  }

  async function retry(clientMessageId: string) {
    setAwaitingBot(true);
    if (!(await chat.retry(clientMessageId))) setAwaitingBot(false);
  }

  return (
    <main className="chat">
      <header className="panel-header">
        <span className="header-avatar">
          {tenant.branding.logoUrl ? <img src={tenant.branding.logoUrl} alt="" /> : <RobotAvatar size={34} />}
        </span>
        <div className="header-text">
          <h1>{tenant.displayName}</h1>
          <HeaderStatus conversation={chat.conversation} online={chat.online} tenant={tenant} />
        </div>
        <CloseButton />
      </header>
      {chat.error ? (
        <StateBody message={chat.error} />
      ) : (
        <>
          <MessageList
            messages={chat.messages}
            options={options}
            onOption={(o) => void send({ body: o.title, optionId: o.id })}
            disabled={!chat.conversation}
            botName={tenant.displayName}
            typing={typing}
            pending={chat.pending}
            onRetry={(id) => void retry(id)}
          />
          <Composer
            onSend={(body) => void send({ body })}
            disabled={!chat.conversation}
            focus={visible}
            asking={chat.conversation?.asking ?? null}
            placeholder={placeholder(chat.conversation, tenant)}
          />
        </>
      )}
    </main>
  );
}

function placeholder(conversation: Conversation | null, tenant: Tenant) {
  if (conversation?.asking === "EMAIL") return "Your email address…";
  if (conversation?.asking === "NAME") return "Your name…";
  const name = conversation?.status === "HUMAN" && conversation.assignee ? conversation.assignee.name : tenant.displayName;
  return tenant.texts.placeholder.replace("{name}", name);
}

function CloseButton() {
  if (!startsHidden) return null;
  return (
    <button type="button" className="icon-button" aria-label="Close chat" onClick={() => postToHost({ type: "chat.close" })}>
      <CloseIcon size={20} />
    </button>
  );
}

// Loading and error screens keep the header, so the panel can always be closed.
function PanelState({ title, message, onRetry }: { title: string; message: string; onRetry?: () => void }) {
  return (
    <main className="chat">
      <header className="panel-header">
        <span className="header-avatar">
          <RobotAvatar size={34} />
        </span>
        <div className="header-text">
          <h1>{title}</h1>
        </div>
        <CloseButton />
      </header>
      <StateBody message={message} onRetry={onRetry} />
    </main>
  );
}

function StateBody({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="panel-state" role="status">
      <RobotAvatar size={64} />
      <p>{message}</p>
      {onRetry && (
        <button type="button" className="button button-quiet" onClick={onRetry}>
          Try again
        </button>
      )}
    </div>
  );
}

function HeaderStatus({ conversation, online, tenant }: { conversation: Conversation | null; online: boolean; tenant: Tenant }) {
  const texts = tenant.texts;
  if (conversation && !online) return <p className="header-status">Reconnecting…</p>;
  switch (conversation?.status) {
    case "WAITING":
      return (
        <p className="header-status">
          <span className={tenant.open ? "dot dot-waiting" : "dot dot-away"} />
          {tenant.open ? texts.waiting : texts.offline}
        </p>
      );
    case "HUMAN":
      return (
        <p className="header-status">
          <span className="dot" />
          {conversation.assignee ? `Chatting with ${conversation.assignee.name}` : "Chatting with our team"}
        </p>
      );
    case "RESOLVED":
      return <p className="header-status">{texts.closed}</p>;
    default:
      return tenant.open ? (
        <p className="header-status"><span className="dot" />{texts.online}</p>
      ) : (
        <p className="header-status"><span className="dot dot-away" />{texts.offline}</p>
      );
  }
}
