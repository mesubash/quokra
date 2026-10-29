import { describe, expect, it } from "vitest";
import type { ConversationSummary } from "../lib/api";
import type { QueueSnapshot } from "../lib/realtime";
import { applySnapshot } from "./useConversationList";

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
    ...overrides,
  };
}

function snapshot(id: string, overrides: Partial<QueueSnapshot> = {}): QueueSnapshot {
  const { lastMessagePreview: _, tenantSlug: __, ...rest } = row(id);
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
