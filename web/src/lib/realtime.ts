import { Client } from "@stomp/stompjs";
import type { Conversation, Message } from "./api";

export type RealtimeEvent =
  | { type: "message.created"; conversationId: string; at: string; data: Message }
  | { type: "conversation.updated"; conversationId: string; at: string; data: Conversation };

interface Handlers {
  onEvent: (event: RealtimeEvent) => void;
  // Called on every (re)connect, so the caller can fetch anything missed while offline.
  onConnect: () => void;
  onDisconnect: () => void;
}

export function connectConversation(token: string, conversationId: string, handlers: Handlers): () => void {
  const protocol = window.location.protocol === "https:" ? "wss" : "ws";
  const client = new Client({
    brokerURL: `${protocol}://${window.location.host}/ws`,
    connectHeaders: { Authorization: `Bearer ${token}` },
    reconnectDelay: 3000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    onConnect: () => {
      client.subscribe(`/topic/conversations/${conversationId}`, (frame) => {
        handlers.onEvent(JSON.parse(frame.body) as RealtimeEvent);
      });
      handlers.onConnect();
    },
    onWebSocketClose: () => handlers.onDisconnect(),
  });
  client.activate();
  return () => void client.deactivate();
}
