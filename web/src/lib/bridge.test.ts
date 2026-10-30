import { afterEach, describe, expect, it, vi } from "vitest";
import { onHostMessage } from "./bridge";

let stop: () => void = () => {};
afterEach(() => stop());

function receive(data: unknown, source: MessageEventSource | null = window.parent) {
  window.dispatchEvent(new MessageEvent("message", { data, source }));
}

describe("onHostMessage", () => {
  it("passes on our messages from the parent window", () => {
    const handler = vi.fn();
    stop = onHostMessage(handler);

    receive({ source: "quokra", type: "chat.open" });
    receive({ source: "quokra", type: "chat.identify", token: "t" });

    expect(handler).toHaveBeenCalledWith({ source: "quokra", type: "chat.open" });
    expect(handler).toHaveBeenCalledWith({ source: "quokra", type: "chat.identify", token: "t" });
  });

  it("ignores other windows, other senders and unknown or malformed messages", () => {
    const handler = vi.fn();
    stop = onHostMessage(handler);
    const other = document.body.appendChild(document.createElement("iframe")).contentWindow;

    receive({ source: "quokra", type: "auth.token", token: "t" }, other);
    receive({ type: "chat.open" });
    receive({ source: "quokra", type: "chat.delete-everything" });
    receive({ source: "quokra", type: "auth.token", token: 42 });

    expect(handler).not.toHaveBeenCalled();
  });
});
