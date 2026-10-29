import { Client, type StompSubscription } from "@stomp/stompjs";
import type { Conversation, Message } from "./api";

export type RealtimeEvent =
  | { type: "message.created"; conversationId: string; at: string; data: Message }
  | { type: "conversation.updated"; conversationId: string; at: string; data: Conversation }
  | { type: "conversation.waiting"; conversationId: string; at: string; data: unknown };

interface SocketHandlers {
  // Called on every (re)connect, so the caller can fetch anything missed while offline.
  onConnect: () => void;
  onDisconnect: () => void;
}

export interface Socket {
  subscribe(destination: string, onEvent: (event: RealtimeEvent) => void): () => void;
  close(): void;
}

// One STOMP connection; subscriptions survive reconnects.
export function openSocket(token: string, handlers: SocketHandlers): Socket {
  const wanted = new Map<string, (event: RealtimeEvent) => void>();
  const active = new Map<string, StompSubscription>();
  const protocol = window.location.protocol === "https:" ? "wss" : "ws";

  const client = new Client({
    brokerURL: `${protocol}://${window.location.host}/ws`,
    connectHeaders: { Authorization: `Bearer ${token}` },
    reconnectDelay: 3000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    onConnect: () => {
      active.clear();
      wanted.forEach((onEvent, destination) => listen(destination, onEvent));
      handlers.onConnect();
    },
    onWebSocketClose: () => handlers.onDisconnect(),
  });

  function listen(destination: string, onEvent: (event: RealtimeEvent) => void) {
    active.set(destination, client.subscribe(destination, (frame) => onEvent(JSON.parse(frame.body) as RealtimeEvent)));
  }

  client.activate();

  return {
    subscribe(destination, onEvent) {
      wanted.set(destination, onEvent);
      if (client.connected) listen(destination, onEvent);
      return () => {
        wanted.delete(destination);
        active.get(destination)?.unsubscribe();
        active.delete(destination);
      };
    },
    close() {
      void client.deactivate();
    },
  };
}

interface ConversationHandlers extends SocketHandlers {
  onEvent: (event: RealtimeEvent) => void;
}

export function connectConversation(token: string, conversationId: string, handlers: ConversationHandlers): () => void {
  const socket = openSocket(token, handlers);
  socket.subscribe(`/topic/conversations/${conversationId}`, handlers.onEvent);
  return () => socket.close();
}
