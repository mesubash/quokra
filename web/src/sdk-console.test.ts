import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import code from "../public/sdk.js?raw";

type AgentConsole = {
  mount: (target: string | Element, o: Record<string, unknown>) => void;
  init: (o: Record<string, unknown>) => void;
  open: () => void;
  close: () => void;
  on: (e: string, fn: (v?: unknown) => void) => void;
  unmount: () => void;
};

function agentConsole(): AgentConsole {
  return (window as unknown as { Quokra: { console: AgentConsole } }).Quokra.console;
}

function frame() {
  return document.querySelector<HTMLIFrameElement>("#quokra-console-frame")!;
}

function fromFrame(data: unknown, origin = window.location.origin, source: Window | null = frame().contentWindow) {
  window.dispatchEvent(new MessageEvent("message", { data, origin, source }));
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

beforeEach(() => {
  new Function(code)();
});

afterEach(() => {
  agentConsole().unmount();
  delete (window as unknown as { Quokra?: unknown }).Quokra;
  document.body.innerHTML = "";
});

describe("sdk.js agent console", () => {
  it("mounts the full console into a container", () => {
    document.body.innerHTML = '<div id="console"></div>';
    agentConsole().mount("#console", { tenant: "demo", getToken: () => "t" });

    expect(frame().parentElement?.id).toBe("console");
    expect(frame().src).toBe(`${window.location.origin}/console?tenant=demo&mode=full&theme=light`);
  });

  it("needs a token getter", () => {
    expect(() => agentConsole().init({ mode: "bubble", tenant: "demo" })).toThrow();
  });

  it("answers the console's token requests, only to the Quokra origin", async () => {
    agentConsole().init({ mode: "bubble", tenant: "demo", getToken: () => Promise.resolve("agent-token") });
    const post = vi.spyOn(frame().contentWindow!, "postMessage");

    fromFrame({ source: "quokra", type: "auth.request", reason: "initial" });
    await flush();

    expect(post).toHaveBeenCalledWith({ type: "auth.token", token: "agent-token", source: "quokra" }, window.location.origin);
  });

  it("doesn't hand out tokens to other origins or windows", async () => {
    const getToken = vi.fn(() => "agent-token");
    agentConsole().init({ mode: "bubble", tenant: "demo", getToken });

    fromFrame({ source: "quokra", type: "auth.request" }, "https://evil.test");
    fromFrame({ source: "quokra", type: "auth.request" }, window.location.origin, window);
    await flush();

    expect(getToken).not.toHaveBeenCalled();
  });

  it("opens the drawer from its button and closes when the console asks", () => {
    agentConsole().init({ mode: "bubble", tenant: "demo", getToken: () => "t" });
    const button = document.querySelector<HTMLButtonElement>(".quokra-console-button")!;

    button.click();
    expect(frame().classList.contains("open")).toBe(true);
    expect(button.getAttribute("aria-expanded")).toBe("true");

    fromFrame({ source: "quokra", type: "console.close" });
    expect(frame().classList.contains("open")).toBe(false);
    expect(document.activeElement).toBe(button);
  });

  it("shows how many customers are waiting", () => {
    const onUnread = vi.fn();
    agentConsole().init({ mode: "bubble", tenant: "demo", getToken: () => "t" });
    agentConsole().on("unread", onUnread);

    fromFrame({ source: "quokra", type: "console.unread", count: 3 });

    expect(onUnread).toHaveBeenCalledWith(3);
    expect(document.querySelector(".quokra-console-button .quokra-badge")?.textContent).toBe("3");
  });

  it("passes the theme and changes it later", () => {
    agentConsole().init({ mode: "bubble", tenant: "demo", getToken: () => "t", theme: "dark" });
    const post = vi.spyOn(frame().contentWindow!, "postMessage");

    expect(frame().src).toContain("&theme=dark");
    (agentConsole() as unknown as { setTheme: (t: string) => void }).setTheme("light");

    expect(post).toHaveBeenCalledWith({ type: "theme.set", theme: "light", source: "quokra" }, window.location.origin);
  });
});
