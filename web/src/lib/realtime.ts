import { Client, type StompSubscription } from "@stomp/stompjs";
import type { Conversation, HandoffReason, Message, Note, Person, Priority, Status } from "./api";

export type RealtimeEvent =
  | { type: "message.created"; conversationId: string; at: string; data: Message }
  | { type: "conversation.updated"; conversationId: string; at: string; data: Conversation }
  | { type: "conversation.waiting"; conversationId: string; at: string; data: QueueSnapshot }
  | { type: "note.created"; conversationId: string; at: string; data: Note };

// What agents get on the queue topic when a conversation changes.
export interface QueueSnapshot {
  id: string;
  tenantId: string;
  status: Status;
  priority: Priority;
  handoffReason: HandoffReason | null;
  customerName: string | null;
  assignee: Person | null;
  lastMessageAt: string;
  waitingSince: string | null;
  resolvedAt: string | null;
}

interface SocketHandlers {
  // Called on every (re)connect, so the caller can fetch anything missed while offline.
  onConnect: () => void;
  onDisconnect: () => void;
}

export interface Socket {
  subscribe(destination: string, onEvent: (event: RealtimeEvent) => void): () => void;
  close(): void;
}

// One STOMP connection; subscriptions survive reconnects. A token getter is read on every
// (re)connect, so a refreshed token is picked up without reopening anything.
export function openSocket(token: string | (() => string), handlers: SocketHandlers): Socket {
  const currentToken = typeof token === "function" ? token : () => token;
  const wanted = new Map<string, (event: RealtimeEvent) => void>();
  const active = new Map<string, StompSubscription>();
  const protocol = window.location.protocol === "https:" ? "wss" : "ws";

  const client = new Client({
    brokerURL: `${protocol}://${window.location.host}/ws`,
    reconnectDelay: 3000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    beforeConnect: () => {
      client.connectHeaders = { Authorization: `Bearer ${currentToken()}` };
    },
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
