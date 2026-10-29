import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Message } from "../lib/api";
import { MessageList } from "./MessageList";

afterEach(cleanup);

const messages: Message[] = [
  { id: "01", senderType: "BOT", senderName: null, body: "Hi!", options: null, clientMessageId: null, createdAt: "" },
  { id: "02", senderType: "CUSTOMER", senderName: null, body: "<b>bold?</b>", options: null, clientMessageId: "c", createdAt: "" },
  { id: "03", senderType: "AGENT", senderName: "Sam", body: "Hello, I'm Sam", options: null, clientMessageId: null, createdAt: "" },
];

describe("MessageList", () => {
  it("shows messages as plain text", () => {
    render(<MessageList messages={messages} options={[]} onOption={() => {}} disabled={false} />);

    expect(screen.getByText("<b>bold?</b>")).toBeTruthy();
    expect(screen.getByText("Sam")).toBeTruthy();
  });

  it("sends the option that was clicked", () => {
    const onOption = vi.fn();
    const option = { id: "pricing", title: "Pricing" };
    render(<MessageList messages={messages} options={[option]} onOption={onOption} disabled={false} />);

    fireEvent.click(screen.getByRole("button", { name: "Pricing" }));

    expect(onOption).toHaveBeenCalledWith(option);
  });
});
