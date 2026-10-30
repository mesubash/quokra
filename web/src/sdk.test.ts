import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
// sdk.js runs on host pages as a plain script, so load it the same way.
import code from "../public/sdk.js?raw";

type Chat = {
  init: (o: Record<string, unknown>) => void;
  identify: (token: string) => void;
  logout: () => void;
  open: () => void;
  close: () => void;
  on: (e: string, fn: (v?: unknown) => void) => void;
  destroy: () => void;
};

function chat(): Chat {
  return (window as unknown as { Quokra: { chat: Chat } }).Quokra.chat;
}

function frame() {
  return document.querySelector<HTMLIFrameElement>("iframe")!;
}

function fromFrame(data: unknown, origin = window.location.origin, source: Window | null = frame().contentWindow) {
  window.dispatchEvent(new MessageEvent("message", { data, origin, source }));
}

beforeEach(() => {
  new Function(code)();
  chat().init({ slug: "demo", pk: "pk_test_demo" });
});

afterEach(() => {
  chat().destroy();
  delete (window as unknown as { Quokra?: unknown }).Quokra;
  document.body.innerHTML = "";
  document.documentElement.style.removeProperty("--quokra-color");
});

describe("sdk.js", () => {
  it("adds a bubble and a hidden panel pointing at the chat", () => {
    expect(frame().src).toBe(`${window.location.origin}/w/demo?pk=pk_test_demo&mode=bubble&theme=light`);
    expect(frame().classList.contains("open")).toBe(false);
    expect(document.querySelector(".quokra-button")).not.toBeNull();
  });

  it("opens and closes the panel from the bubble", () => {
    const button = document.querySelector<HTMLButtonElement>(".quokra-button")!;

    button.click();
    expect(frame().classList.contains("open")).toBe(true);
    button.click();
    expect(frame().classList.contains("open")).toBe(false);
  });

  it("shows the unread count sent by its own frame", () => {
    const onUnread = vi.fn();
    chat().on("unread", onUnread);

    fromFrame({ source: "quokra", type: "chat.unread", count: 2 });

    expect(onUnread).toHaveBeenCalledWith(2);
    expect(document.querySelector(".quokra-badge")?.textContent).toBe("2");
  });

  it("ignores messages from another origin", () => {
    const onUnread = vi.fn();
    chat().on("unread", onUnread);

    fromFrame({ source: "quokra", type: "chat.unread", count: 2 }, "https://evil.test");

    expect(onUnread).not.toHaveBeenCalled();
  });

  it("ignores messages from another window", () => {
    const onUnread = vi.fn();
    chat().on("unread", onUnread);

    fromFrame({ source: "quokra", type: "chat.unread", count: 2 }, window.location.origin, window);

    expect(onUnread).not.toHaveBeenCalled();
  });

  it("ignores messages that aren't ours", () => {
    const onUnread = vi.fn();
    chat().on("unread", onUnread);

    fromFrame({ type: "chat.unread", count: 2 });

    expect(onUnread).not.toHaveBeenCalled();
  });

  it("closes when the panel asks", () => {
    chat().open();

    fromFrame({ source: "quokra", type: "chat.close" });

    expect(frame().classList.contains("open")).toBe(false);
  });

  it("only uses a plain colour from the panel", () => {
    const color = () => document.documentElement.style.getPropertyValue("--quokra-color");

    fromFrame({ source: "quokra", type: "chat.ready", color: "red;background:url(x)" });
    expect(color()).toBe("");

    fromFrame({ source: "quokra", type: "chat.ready", color: "#0A7C66" });
    expect(color()).toBe("#0A7C66");
  });

  it("tells screen readers whether the chat is open", () => {
    const button = document.querySelector<HTMLButtonElement>(".quokra-button")!;
    expect(button.getAttribute("aria-expanded")).toBe("false");

    button.click();

    expect(button.getAttribute("aria-expanded")).toBe("true");
    expect(button.getAttribute("aria-controls")).toBe(frame().id);
  });
});

describe("sdk.js identify", () => {
  it("passes the identity token to its own frame once the panel is ready", () => {
    chat().identify("id-token");
    const post = vi.spyOn(frame().contentWindow!, "postMessage");

    fromFrame({ source: "quokra", type: "chat.ready" });

    expect(post).toHaveBeenCalledWith({ source: "quokra", type: "chat.identify", token: "id-token" }, window.location.origin);
  });

  it("sends it straight away when the panel is already ready, and only once", () => {
    fromFrame({ source: "quokra", type: "chat.ready" });
    const post = vi.spyOn(frame().contentWindow!, "postMessage");

    chat().identify("id-token");
    fromFrame({ source: "quokra", type: "chat.ready" });

    expect(post.mock.calls.filter(([m]) => (m as { type: string }).type === "chat.identify")).toHaveLength(1);
  });

  it("logs out before identifying the next customer", () => {
    chat().identify("old-customer");
    chat().logout();
    chat().identify("new-customer");
    const post = vi.spyOn(frame().contentWindow!, "postMessage");

    fromFrame({ source: "quokra", type: "chat.ready" });

    const types = post.mock.calls.map(([m]) => (m as { type: string; token?: string }));
    expect(types.filter((m) => m.type !== "chat.open")).toEqual([
      { source: "quokra", type: "chat.logout" },
      { source: "quokra", type: "chat.identify", token: "new-customer" },
    ]);
  });

  it("reports the result", () => {
    const identified = vi.fn();
    const failed = vi.fn();
    chat().on("identified", identified);
    chat().on("identifyfailed", failed);

    fromFrame({ source: "quokra", type: "chat.identified" });
    fromFrame({ source: "quokra", type: "chat.identify.failed", code: "IDENTITY_TOKEN_EXPIRED" });

    expect(identified).toHaveBeenCalled();
    expect(failed).toHaveBeenCalledWith("IDENTITY_TOKEN_EXPIRED");
  });
});

describe("sdk.js greeting card", () => {
  const teaser = () => document.querySelector(".quokra-teaser");

  beforeEach(() => {
    vi.useFakeTimers();
    sessionStorage.clear();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("appears a few seconds after the panel is ready", () => {
    fromFrame({ source: "quokra", type: "chat.ready", teaser: "Hi! Need any help?" });
    expect(teaser()).toBeNull();

    vi.advanceTimersByTime(5000);

    expect(teaser()?.textContent).toContain("Hi! Need any help?");
  });

  it("opens the chat when clicked", () => {
    fromFrame({ source: "quokra", type: "chat.ready", teaser: "Hi!" });
    vi.advanceTimersByTime(5000);

    document.querySelector<HTMLButtonElement>(".quokra-teaser-text")!.click();

    expect(frame().classList.contains("open")).toBe(true);
    expect(teaser()).toBeNull();
  });

  it("stays away for the rest of the visit once dismissed", () => {
    fromFrame({ source: "quokra", type: "chat.ready", teaser: "Hi!" });
    vi.advanceTimersByTime(5000);
    document.querySelector<HTMLButtonElement>(".quokra-teaser-close")!.click();

    fromFrame({ source: "quokra", type: "chat.ready", teaser: "Hi!" });
    vi.advanceTimersByTime(5000);

    expect(teaser()).toBeNull();
  });

  it("isn't shown if the chat was already opened", () => {
    chat().open();
    fromFrame({ source: "quokra", type: "chat.ready", teaser: "Hi!" });
    vi.advanceTimersByTime(5000);

    expect(teaser()).toBeNull();
  });

  it("isn't shown without a teaser text", () => {
    fromFrame({ source: "quokra", type: "chat.ready" });
    vi.advanceTimersByTime(10000);

    expect(teaser()).toBeNull();
  });
});
