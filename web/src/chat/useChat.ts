import { useCallback, useEffect, useRef, useState } from "react";
import { widgetApi, type Conversation, type Message } from "../lib/api";
import { connectConversation } from "../lib/realtime";
import { mergeMessages } from "./messages";

export interface ChatState {
  conversation: Conversation | null;
  messages: Message[];
  online: boolean;
  error: string | null;
}

export function useChat(token: string | null) {
  const [state, setState] = useState<ChatState>({ conversation: null, messages: [], online: false, error: null });
  const messagesRef = useRef<Message[]>([]);
  messagesRef.current = state.messages;

  const addMessages = useCallback((incoming: Message[]) => {
    setState((s) => ({ ...s, messages: mergeMessages(s.messages, incoming) }));
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
            else setState((s) => ({ ...s, conversation: event.data }));
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
      .catch(() => !cancelled && setState((s) => ({ ...s, error: "Chat is unavailable right now." })));

    return () => {
      cancelled = true;
      stop?.();
    };
  }, [token, addMessages]);

  const send = useCallback(
    async (message: { body?: string; optionId?: string }) => {
      if (!token) return;
      const { message: saved, conversation } = await widgetApi.send(token, {
        clientMessageId: crypto.randomUUID(),
        ...message,
      });
      addMessages([saved]);
      setState((s) => ({ ...s, conversation }));
    },
    [token, addMessages],
  );

  return { ...state, send };
}
