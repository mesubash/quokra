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
    customer: { sub: null, contactId: "p1", name: "Pat", email: "pat@example.test", blocked: false },
    assignee: null,
    createdAt: "2026-10-29T10:00:00Z",
    lastMessageAt: "2026-10-29T10:00:00Z",
    waitingSince: "2026-10-29T10:00:00Z",
    resolvedAt: null,
    ...overrides,
  };
}

const taken = (c: AgentConversation): AgentConversation => ({ ...c, status: "HUMAN", assignee: { sub: "sam", name: "Sam" } });

// Acts like the server: actions change the conversation, and the next detail call returns it.
function fakeApi(start: AgentConversation, extra: Partial<AgentApi> = {}) {
  let current = start;
  const change = (next: AgentConversation) => {
    current = next;
    return Promise.resolve({ conversation: next });
  };
  return {
    detail: vi.fn(() => Promise.resolve({ conversation: current, messages: [], events: [], notes: [] })),
    accept: vi.fn(() => change(taken(current))),
    markRead: vi.fn(() => Promise.resolve()),
    reply: vi.fn((_id: string, clientMessageId: string, body: string) =>
      Promise.resolve({
        message: { id: "m9", senderType: "AGENT", senderName: "Sam", body, options: null, clientMessageId, createdAt: "" },
      }),
    ),
    note: vi.fn((_id: string, body: string) =>
      Promise.resolve({ note: { id: "n1", author: { sub: "sam", name: "Sam" }, body, createdAt: "" } }),
    ),
    ...extra,
  } as unknown as AgentApi & Record<string, ReturnType<typeof vi.fn>>;
}

function renderView(api: AgentApi) {
  return render(
    <ConversationView id="c1" api={api} me={me} socket={socket} onBack={() => {}} onChanged={() => {}} onError={() => {}} />,
  );
}

function send(text: string) {
  const input = screen.getByLabelText("Message");
  fireEvent.change(input, { target: { value: text } });
  fireEvent.keyDown(input, { key: "Enter" });
}

describe("ConversationView", () => {
  it("offers Accept for a waiting conversation and shows who it is", async () => {
    renderView(fakeApi(conversation()));

    expect(await screen.findByRole("button", { name: "Accept" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Pat: show details" })).toBeTruthy();
    expect(screen.getByText("· pat@example.test")).toBeTruthy();
    expect(screen.getByText("Sending assigns this chat to you")).toBeTruthy();
  });

  it("accepts and replies in one go", async () => {
    const api = fakeApi(conversation());
    renderView(api);
    await screen.findByRole("button", { name: "Accept" });

    send("Hi Pat");

    await waitFor(() => expect(api.reply).toHaveBeenCalledWith("c1", expect.any(String), "Hi Pat"));
    expect(api.accept).toHaveBeenCalled();
    expect(await screen.findByText("Hi Pat")).toBeTruthy();
  });

  it("puts resolve, transfer and block in the menu once it's mine", async () => {
    renderView(fakeApi(taken(conversation())));

    fireEvent.click(await screen.findByRole("button", { name: "More actions" }));

    expect(screen.getByRole("menuitem", { name: "Resolve" })).toBeTruthy();
    expect(screen.getByRole("menuitem", { name: "Transfer…" })).toBeTruthy();
    expect(screen.getByRole("menuitem", { name: "Return to bot" })).toBeTruthy();
    expect(screen.getByRole("menuitem", { name: "Block contact…" })).toBeTruthy();
  });

  it("only allows a private note on someone else's conversation", async () => {
    const api = fakeApi(conversation({ status: "HUMAN", assignee: { sub: "alex", name: "Alex" } }));
    renderView(api);

    expect(await screen.findByText("Alex is handling this chat. You can leave a private note.")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "More actions" })).toBeNull();

    send("Customer called too");

    await waitFor(() => expect(api.note).toHaveBeenCalledWith("c1", "Customer called too"));
    expect(api.reply).not.toHaveBeenCalled();
    expect(await screen.findByText("Private note · Sam")).toBeTruthy();
  });

  it("offers Reopen on a resolved conversation", async () => {
    renderView(fakeApi(conversation({ status: "RESOLVED" })));

    expect(await screen.findByRole("button", { name: "Reopen" })).toBeTruthy();
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
