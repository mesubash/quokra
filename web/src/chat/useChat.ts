import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError, widgetApi, type Conversation, type Message } from "../lib/api";
import { connectConversation } from "../lib/realtime";
import { mergeMessages } from "./messages";

// A message the customer sent that the server hasn't confirmed yet.
export interface PendingMessage {
  clientMessageId: string;
  body: string;
  optionId?: string;
  status: "sending" | "failed";
}

export interface ChatState {
  conversation: Conversation | null;
  messages: Message[];
  pending: PendingMessage[];
  online: boolean;
  error: string | null;
}

// onUnauthorized: the server rejected the session token; the caller should get a new session.
export function useChat(token: string | null, onUnauthorized?: () => void) {
  const [state, setState] = useState<ChatState>({
    conversation: null,
    messages: [],
    pending: [],
    online: false,
    error: null,
  });
  const messagesRef = useRef<Message[]>([]);
  messagesRef.current = state.messages;

  // A stored message can arrive over the socket before the send request returns: drop its pending copy.
  const addMessages = useCallback((incoming: Message[]) => {
    const confirmed = new Set(incoming.map((m) => m.clientMessageId).filter(Boolean));
    setState((s) => ({
      ...s,
      messages: mergeMessages(s.messages, incoming),
      pending: s.pending.filter((p) => !confirmed.has(p.clientMessageId)),
    }));
  }, []);

  useEffect(() => {
    if (!token) return;
    let stop: (() => void) | undefined;
    let cancelled = false;

    widgetApi
      .startConversation(token)
      .then(({ conversation, messages }) => {
        if (cancelled) return;
        setState((s) => ({ ...s, conversation, messages: mergeMessages(s.messages, messages) }));
        stop = connectConversation(token, conversation.id, {
          onEvent: (event) => {
            if (event.type === "message.created") addMessages([event.data]);
            else if (event.type === "conversation.updated") setState((s) => ({ ...s, conversation: event.data }));
          },
          onConnect: () => {
            setState((s) => ({ ...s, online: true }));
            const last = messagesRef.current[messagesRef.current.length - 1];
            if (last) {
              widgetApi.messagesAfter(token, last.id).then((page) => addMessages(page.items)).catch(() => {});
            }
          },
          onDisconnect: () => setState((s) => ({ ...s, online: false })),
        });
      })
      .catch((e) => {
        if (cancelled) return;
        if (e instanceof ApiError && e.status === 401 && onUnauthorized) onUnauthorized();
        else setState((s) => ({ ...s, error: "Chat is unavailable right now." }));
      });

    return () => {
      cancelled = true;
      stop?.();
    };
  }, [token, addMessages, onUnauthorized]);

  // Shows the message straight away as "sending", then swaps in the stored one.
  // A retry reuses the same clientMessageId, so the server never stores it twice.
  const deliver = useCallback(
    async (item: PendingMessage): Promise<boolean> => {
      if (!token) return false;
      setState((s) => ({
        ...s,
        pending: [...s.pending.filter((p) => p.clientMessageId !== item.clientMessageId), { ...item, status: "sending" }],
      }));
      try {
        const { message: saved, conversation } = await widgetApi.send(token, {
          clientMessageId: item.clientMessageId,
          ...(item.optionId ? { optionId: item.optionId } : { body: item.body }),
        });
        setState((s) => ({
          ...s,
          conversation,
          messages: mergeMessages(s.messages, [saved]),
          pending: s.pending.filter((p) => p.clientMessageId !== item.clientMessageId),
        }));
        return true;
      } catch {
        setState((s) => ({
          ...s,
          pending: s.pending.map((p) => (p.clientMessageId === item.clientMessageId ? { ...p, status: "failed" } : p)),
        }));
        return false;
      }
    },
    [token],
  );

  // body is what the customer sees in their bubble (for a menu button, its title).
  const send = useCallback(
    (message: { body: string; optionId?: string }) =>
      deliver({ clientMessageId: crypto.randomUUID(), status: "sending", ...message }),
    [deliver],
  );

  const retry = useCallback(
    (clientMessageId: string) => {
      const item = state.pending.find((p) => p.clientMessageId === clientMessageId);
      return item ? deliver(item) : Promise.resolve(false);
    },
    [state.pending, deliver],
  );

  return { ...state, send, retry };
}
