import type { NotifyPrefs } from "../lib/notifyPrefs";
import type { RealtimeEvent } from "../lib/realtime";
import type { Tune } from "../lib/sound";

// Something worth telling the agent about.
export interface Notice {
  kind: Tune;
  title: string;
  body: string;
  conversationId: string;
}

// Turns a queue event into a notice for this agent, or null if it isn't for them (or they turned
// that kind off, or they're already looking at that chat).
export function noticeFor(
  event: RealtimeEvent,
  me: string,
  prefs: NotifyPrefs,
  watching: string | null,
): Notice | null {
  switch (event.type) {
    case "conversation.waiting": {
      if (!prefs.waiting) return null;
      const name = event.data.customerName ?? "A customer";
      return {
        kind: "waiting",
        title: "New chat waiting",
        body: `${name} would like to talk to someone.`,
        conversationId: event.conversationId,
      };
    }
    case "conversation.activity": {
      const a = event.data;
      if (!prefs.myMessages || a.senderType !== "CUSTOMER" || a.assigneeSub !== me || watching === a.id) return null;
      return { kind: "message", title: a.customerName ?? "Customer", body: a.lastMessagePreview, conversationId: a.id };
    }
    case "conversation.transferred": {
      const t = event.data;
      if (!prefs.transfers || t.toSub !== me) return null;
      return {
        kind: "transfer",
        title: "Chat transferred to you",
        body: `${t.byName} passed you the chat with ${t.customerName ?? "a customer"}.`,
        conversationId: t.id,
      };
    }
    case "note.added": {
      const n = event.data;
      if (!prefs.notes || n.assigneeSub !== me || n.authorSub === me || watching === n.id) return null;
      return { kind: "note", title: `Note from ${n.authorName}`, body: n.preview, conversationId: n.id };
    }
    default:
      return null;
  }
}
