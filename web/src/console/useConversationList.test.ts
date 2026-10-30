import { describe, expect, it } from "vitest";
import type { ConversationSummary } from "../lib/api";
import type { QueueSnapshot } from "../lib/realtime";
import { applyActivity, applySnapshot } from "./useConversationList";

function row(id: string, overrides: Partial<ConversationSummary> = {}): ConversationSummary {
  return {
    id,
    tenantSlug: "acme",
    status: "WAITING",
    priority: "NORMAL",
    handoffReason: "CUSTOMER_REQUEST",
    customerName: null,
    lastMessagePreview: "hello",
    lastMessageAt: "2026-10-29T10:00:00Z",
    waitingSince: "2026-10-29T10:00:00Z",
    assignee: null,
    unreadCount: 0,
    ...overrides,
  };
}

function snapshot(id: string, overrides: Partial<QueueSnapshot> = {}): QueueSnapshot {
  const { lastMessagePreview: _, tenantSlug: __, unreadCount: ___, ...rest } = row(id);
  return { ...rest, tenantId: "t1", resolvedAt: null, ...overrides };
}

describe("applySnapshot", () => {
  it("adds a new waiting chat in queue order, urgent first", () => {
    const items = [row("a", { waitingSince: "2026-10-29T09:00:00Z" })];

    const next = applySnapshot(items, "queue", snapshot("b", { priority: "URGENT" }), "sam", "acme");

    expect(next.map((c) => c.id)).toEqual(["b", "a"]);
  });

  it("drops a chat that someone accepted from the queue", () => {
    const next = applySnapshot(
      [row("a"), row("b")],
      "queue",
      snapshot("a", { status: "HUMAN", assignee: { sub: "alex", name: "Alex" } }),
      "sam",
      "acme",
    );

    expect(next.map((c) => c.id)).toEqual(["b"]);
  });

  it("only adds chats assigned to me to Mine, keeping the preview", () => {
    const mine = applySnapshot(
      [row("a", { status: "HUMAN", assignee: { sub: "sam", name: "Sam" } })],
      "mine",
      snapshot("a", { status: "HUMAN", assignee: { sub: "sam", name: "Sam" } }),
      "sam",
      "acme",
    );
    const theirs = applySnapshot([], "mine", snapshot("c", { status: "HUMAN", assignee: { sub: "alex", name: "Alex" } }), "sam", "acme");

    expect(mine[0].lastMessagePreview).toBe("hello");
    expect(theirs).toEqual([]);
  });
});

describe("applyActivity", () => {
  const message = (id: string, senderType: "CUSTOMER" | "AGENT" = "CUSTOMER") => ({
    id,
    lastMessagePreview: "new message",
    lastMessageAt: "2026-10-29T11:00:00Z",
    senderType,
    assigneeSub: null,
    customerName: null,
  });

  it("updates the preview and counts customer messages as unread", () => {
    const next = applyActivity([row("a"), row("b")], "mine", message("a"), null);

    expect(next[0]).toMatchObject({ id: "a", lastMessagePreview: "new message", unreadCount: 1 });
  });

  it("doesn't count messages in the open chat or from agents", () => {
    expect(applyActivity([row("a")], "mine", message("a"), "a")[0].unreadCount).toBe(0);
    expect(applyActivity([row("a")], "mine", message("a", "AGENT"), null)[0].unreadCount).toBe(0);
  });

  it("ignores conversations that aren't in the list", () => {
    const items = [row("a")];

    expect(applyActivity(items, "mine", message("zzz"), null)).toBe(items);
  });
});
