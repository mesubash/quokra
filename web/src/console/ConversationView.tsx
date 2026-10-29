import { useCallback, useEffect, useState } from "react";
import { ApiError, type AgentApi, type AgentConversation, type Me, type Message } from "../lib/api";
import type { Socket } from "../lib/realtime";
import { Composer } from "../chat/Composer";
import { MessageList } from "../chat/MessageList";
import { mergeMessages } from "../chat/messages";
import { priorityLabel, reasonLabel } from "./format";

interface Props {
  id: string;
  api: AgentApi;
  me: Me;
  socket: Socket;
  onChanged: () => void;
  onError: (e: unknown) => void;
}

const STATUS_LABEL = { BOT: "With the bot", WAITING: "Waiting", HUMAN: "In progress", RESOLVED: "Resolved" };

export function ConversationView({ id, api, me, socket, onChanged, onError }: Props) {
  const [conversation, setConversation] = useState<AgentConversation | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    api
      .detail(id)
      .then((d) => {
        setConversation(d.conversation);
        setMessages((current) => mergeMessages(current, d.messages));
      })
      .catch(onError);
  }, [api, id, onError]);

  useEffect(load, [load]);

  // New messages arrive live; status changes reload the agent's view of the conversation.
  useEffect(
    () =>
      socket.subscribe(`/topic/conversations/${id}`, (event) => {
        if (event.type === "message.created") setMessages((current) => mergeMessages(current, [event.data]));
        else load();
      }),
    [socket, id, load],
  );

  async function act(action: () => Promise<{ conversation: AgentConversation }>) {
    setBusy(true);
    setNotice(null);
    try {
      setConversation((await action()).conversation);
      onChanged();
    } catch (e) {
      if (e instanceof ApiError && e.code === "ALREADY_ACCEPTED") setNotice("Someone else already took this conversation.");
      else if (e instanceof ApiError && e.status === 409) setNotice("This conversation has changed. Showing the latest.");
      else onError(e);
      load();
      onChanged();
    } finally {
      setBusy(false);
    }
  }

  async function reply(body: string) {
    try {
      const { message } = await api.reply(id, crypto.randomUUID(), body);
      setMessages((current) => mergeMessages(current, [message]));
    } catch (e) {
      setNotice("Couldn't send. Try again.");
      onError(e);
    }
  }

  if (!conversation) return <p className="console-placeholder muted">Loading…</p>;

  const mine = conversation.assignee?.sub === me.sub;
  const customer = conversation.customer?.name ?? "Guest";

  return (
    <div className="conversation">
      <header className="conversation-header">
        <div>
          <h2>{customer}</h2>
          <p className="muted">
            {conversation.customer?.email ?? "Not identified"} · {conversation.tenantSlug}
          </p>
        </div>
        <div className="conversation-tags">
          <span className={`badge status-${conversation.status.toLowerCase()}`}>{STATUS_LABEL[conversation.status]}</span>
          {conversation.priority !== "NORMAL" && (
            <span className={`badge badge-${conversation.priority.toLowerCase()}`}>{priorityLabel(conversation.priority)}</span>
          )}
          {conversation.handoffReason && <span className="badge">{reasonLabel(conversation.handoffReason)}</span>}
          {conversation.assignee && !mine && <span className="badge">With {conversation.assignee.name}</span>}
        </div>
        <div className="conversation-actions">
          {conversation.status === "WAITING" && (
            <button type="button" className="button" disabled={busy} onClick={() => act(() => api.accept(id))}>
              Accept
            </button>
          )}
          {conversation.status === "HUMAN" && mine && (
            <button type="button" className="button button-quiet" disabled={busy} onClick={() => act(() => api.returnToBot(id))}>
              Return to bot
            </button>
          )}
          {(conversation.status === "WAITING" || (conversation.status === "HUMAN" && mine)) && (
            <button type="button" className="button button-quiet" disabled={busy} onClick={() => act(() => api.resolve(id))}>
              Resolve
            </button>
          )}
        </div>
      </header>
      {notice && <p className="notice">{notice}</p>}
      <MessageList
        messages={messages}
        options={[]}
        onOption={() => {}}
        disabled
        botName="Bot"
        mine="AGENT"
        customerName={customer}
      />
      {conversation.status === "HUMAN" && mine ? (
        <Composer onSend={(body) => void reply(body)} disabled={false} focus placeholder={`Reply to ${customer}…`} />
      ) : (
        <p className="conversation-hint muted">
          {conversation.status === "WAITING" ? "Accept the conversation to reply." : "Only the assigned agent can reply."}
        </p>
      )}
    </div>
  );
}
