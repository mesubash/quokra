import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, type AgentApi, type AgentConversation } from "../lib/api";
import type { Socket } from "../lib/realtime";
import { ConversationView } from "./ConversationView";

afterEach(cleanup);

const me = { sub: "sam", name: "Sam", tenants: ["acme"] };
const socket: Socket = { subscribe: () => () => {}, close: () => {} };

function conversation(overrides: Partial<AgentConversation> = {}): AgentConversation {
  return {
    id: "c1",
    tenantSlug: "acme",
    status: "WAITING",
    priority: "URGENT",
    handoffReason: "URGENT",
    customer: null,
    assignee: null,
    createdAt: "2026-10-29T10:00:00Z",
    lastMessageAt: "2026-10-29T10:00:00Z",
    waitingSince: "2026-10-29T10:00:00Z",
    resolvedAt: null,
    ...overrides,
  };
}

function fakeApi(c: AgentConversation, extra: Partial<AgentApi> = {}): AgentApi {
  return {
    detail: vi.fn().mockResolvedValue({ conversation: c, messages: [], events: [] }),
    accept: vi.fn().mockResolvedValue({ conversation: { ...c, status: "HUMAN", assignee: { sub: "sam", name: "Sam" } } }),
    ...extra,
  } as unknown as AgentApi;
}

function renderView(api: AgentApi) {
  return render(<ConversationView id="c1" api={api} me={me} socket={socket} onChanged={() => {}} onError={() => {}} />);
}

describe("ConversationView", () => {
  it("offers Accept for a waiting conversation and shows why it's here", async () => {
    renderView(fakeApi(conversation()));

    expect(await screen.findByRole("button", { name: "Accept" })).toBeTruthy();
    expect(screen.getByText("Urgent keyword")).toBeTruthy();
    expect(screen.getByText("Accept the conversation to reply.")).toBeTruthy();
  });

  it("lets me reply, resolve or hand back once it's mine", async () => {
    const api = fakeApi(conversation());
    renderView(api);

    fireEvent.click(await screen.findByRole("button", { name: "Accept" }));

    await waitFor(() => expect(screen.getByRole("button", { name: "Return to bot" })).toBeTruthy());
    expect(screen.getByRole("button", { name: "Resolve" })).toBeTruthy();
    expect(screen.getByLabelText("Message")).toBeTruthy();
  });

  it("doesn't let me reply to someone else's conversation", async () => {
    renderView(fakeApi(conversation({ status: "HUMAN", assignee: { sub: "alex", name: "Alex" } })));

    expect(await screen.findByText("Only the assigned agent can reply.")).toBeTruthy();
    expect(screen.getByText("With Alex")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Resolve" })).toBeNull();
  });

  it("explains when another agent accepted first", async () => {
    const api = fakeApi(conversation(), {
      accept: vi.fn().mockRejectedValue(new ApiError(409, "ALREADY_ACCEPTED", "taken")),
    });
    renderView(api);

    fireEvent.click(await screen.findByRole("button", { name: "Accept" }));

    expect(await screen.findByText("Someone else already took this conversation.")).toBeTruthy();
  });
});
