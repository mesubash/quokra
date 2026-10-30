import { describe, expect, it } from "vitest";
import { DEFAULT_PREFS } from "../lib/notifyPrefs";
import type { RealtimeEvent } from "../lib/realtime";
import { noticeFor } from "./notifications";

const at = "2026-10-30T10:00:00Z";

const activity = (assigneeSub: string | null, senderType: "CUSTOMER" | "AGENT" = "CUSTOMER"): RealtimeEvent => ({
  type: "conversation.activity",
  conversationId: "c1",
  at,
  data: { id: "c1", lastMessagePreview: "Hello?", lastMessageAt: at, senderType, assigneeSub, customerName: "Pat" },
});

describe("noticeFor", () => {
  it("tells me about customers writing in my chats, unless I'm looking at it", () => {
    expect(noticeFor(activity("sam"), "sam", DEFAULT_PREFS, null)).toMatchObject({ kind: "message", title: "Pat" });
    expect(noticeFor(activity("sam"), "sam", DEFAULT_PREFS, "c1")).toBeNull();
    expect(noticeFor(activity("alex"), "sam", DEFAULT_PREFS, null)).toBeNull();
    expect(noticeFor(activity("sam", "AGENT"), "sam", DEFAULT_PREFS, null)).toBeNull();
  });

  it("tells only the agent receiving a transfer", () => {
    const event: RealtimeEvent = {
      type: "conversation.transferred",
      conversationId: "c1",
      at,
      data: { id: "c1", toSub: "sam", toName: "Sam", byName: "Alex", customerName: "Pat" },
    };

    expect(noticeFor(event, "sam", DEFAULT_PREFS, null)?.body).toBe("Alex passed you the chat with Pat.");
    expect(noticeFor(event, "alex", DEFAULT_PREFS, null)).toBeNull();
  });

  it("tells me about a colleague's note on my chat, not my own", () => {
    const note = (authorSub: string): RealtimeEvent => ({
      type: "note.added",
      conversationId: "c1",
      at,
      data: { id: "c1", authorSub, authorName: "Alex", assigneeSub: "sam", customerName: "Pat", preview: "VIP" },
    });

    expect(noticeFor(note("alex"), "sam", DEFAULT_PREFS, null)).toMatchObject({ kind: "note", title: "Note from Alex" });
    expect(noticeFor(note("sam"), "sam", DEFAULT_PREFS, null)).toBeNull();
  });

  it("respects what I turned off", () => {
    expect(noticeFor(activity("sam"), "sam", { ...DEFAULT_PREFS, myMessages: false }, null)).toBeNull();
  });
});
