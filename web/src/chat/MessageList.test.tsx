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
  it("shows an image message as the image, fetched with the page's token", async () => {
    URL.createObjectURL = vi.fn(() => "blob:shot");
    URL.revokeObjectURL = vi.fn();
    const loadImage = vi.fn(() => Promise.resolve(new Blob(["png"])));
    const image = { ...message("06", "CUSTOMER", "Sent an image"), attachment: { name: "shot.png", contentType: "image/png", size: 3 } };

    renderList({ messages: [image], loadImage });

    const img = (await screen.findByAltText("shot.png")) as HTMLImageElement;
    expect(img.src).toBe("blob:shot");
    expect(loadImage).toHaveBeenCalledWith("06");
    expect(screen.queryByText("Sent an image")).toBeNull();

    fireEvent.click(screen.getByLabelText("Show full size"));
    expect(screen.getByRole("dialog", { name: "Image" })).toBeTruthy();
    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("dialog")).toBeNull();
  });

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

  it("shows Sent under the customer's latest message until someone replies", () => {
    renderList({ messages: [message("01", "BOT", "Hi!"), message("02", "CUSTOMER", "Hello")] });

    expect(screen.getByText(/^Sent/)).toBeTruthy();
  });

  it("doesn't show Sent once there is a reply", () => {
    renderList();

    expect(screen.queryByText(/^Sent/)).toBeNull();
  });

  it("shows messages that are still sending", () => {
    renderList({ pending: [{ clientMessageId: "c-1", body: "On its way", status: "sending" }] });

    expect(screen.getByText("On its way")).toBeTruthy();
    expect(screen.getByText("Sending…")).toBeTruthy();
  });

  it("offers a retry when sending failed", () => {
    const onRetry = vi.fn();
    renderList({ pending: [{ clientMessageId: "c-1", body: "Oops", status: "failed" }], onRetry });

    fireEvent.click(screen.getByRole("button", { name: "Retry" }));

    expect(onRetry).toHaveBeenCalledWith("c-1");
  });

  it("shows the typing dots instead of options while the bot is answering", () => {
    renderList({ options: [{ id: "pricing", title: "Pricing" }], typing: true });

    expect(screen.getByLabelText("Acme is typing")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Pricing" })).toBeNull();
  });
});
