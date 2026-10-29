import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Message } from "../lib/api";
import { MessageList } from "./MessageList";

afterEach(cleanup);

function message(id: string, senderType: Message["senderType"], body: string, senderName: string | null = null): Message {
  return { id, senderType, senderName, body, options: null, clientMessageId: null, createdAt: "2026-10-29T10:00:00Z" };
}

const messages: Message[] = [
  message("01", "BOT", "Hi!"),
  message("02", "BOT", "How can we help?"),
  message("03", "CUSTOMER", "<b>bold?</b>"),
  message("04", "SYSTEM", "Sam joined the chat"),
  message("05", "AGENT", "Hello, I'm Sam", "Sam Lee"),
];

function renderList(extra: Partial<Parameters<typeof MessageList>[0]> = {}) {
  return render(
    <MessageList messages={messages} options={[]} onOption={() => {}} disabled={false} botName="Acme" {...extra} />,
  );
}

describe("MessageList", () => {
  it("shows messages as plain text", () => {
    renderList();

    expect(screen.getByText("<b>bold?</b>")).toBeTruthy();
  });

  it("groups consecutive bot messages under one name", () => {
    renderList();

    expect(screen.getAllByText("Acme")).toHaveLength(1);
    expect(screen.getByText("Hi!")).toBeTruthy();
    expect(screen.getByText("How can we help?")).toBeTruthy();
  });

  it("names the agent and shows their initials", () => {
    renderList();

    expect(screen.getByText("Sam Lee")).toBeTruthy();
    expect(screen.getByText("SL")).toBeTruthy();
  });

  it("shows system messages as a note", () => {
    renderList();

    expect(screen.getByText("Sam joined the chat").className).toBe("system-note");
  });

  it("sends the option that was clicked", () => {
    const onOption = vi.fn();
    const option = { id: "pricing", title: "Pricing" };
    renderList({ options: [option], onOption });

    fireEvent.click(screen.getByRole("button", { name: "Pricing" }));

    expect(onOption).toHaveBeenCalledWith(option);
  });

  it("shows the typing dots instead of options while the bot is answering", () => {
    renderList({ options: [{ id: "pricing", title: "Pricing" }], typing: true });

    expect(screen.getByLabelText("Acme is typing")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Pricing" })).toBeNull();
  });
});
