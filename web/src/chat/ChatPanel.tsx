import { useEffect, useState } from "react";
import { widgetApi, type Conversation, type Tenant } from "../lib/api";
import { Composer } from "./Composer";
import { MessageList } from "./MessageList";
import { activeOptions } from "./messages";
import { getSession } from "./session";
import { useChat } from "./useChat";

type Setup =
  | { status: "loading" }
  | { status: "ready"; tenant: Tenant; token: string }
  | { status: "error"; message: string };

export function ChatPanel({ slug }: { slug: string }) {
  const pk = new URLSearchParams(window.location.search).get("pk");
  const [setup, setSetup] = useState<Setup>({ status: "loading" });

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
      })
      .catch(() => !cancelled && setSetup({ status: "error", message: "Chat is unavailable right now." }));
    return () => {
      cancelled = true;
    };
  }, [pk, slug]);

  if (setup.status === "loading") return <p className="panel muted">Loading…</p>;
  if (setup.status === "error") return <p className="panel muted">{setup.message}</p>;
  return <Chat tenant={setup.tenant} token={setup.token} />;
}

function Chat({ tenant, token }: { tenant: Tenant; token: string }) {
  const chat = useChat(token);
  const options = chat.conversation?.status === "BOT" ? activeOptions(chat.messages) : [];

  return (
    <main className="chat">
      <header className="panel-header">
        {tenant.branding.logoUrl && <img src={tenant.branding.logoUrl} alt="" height={24} />}
        <h1>{tenant.displayName}</h1>
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
