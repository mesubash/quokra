import { afterEach, describe, expect, it } from "vitest";
import { shortcutFor, stepThrough } from "./shortcuts";

afterEach(() => {
  document.body.innerHTML = "";
});

function press(key: string, init: KeyboardEventInit = {}, target: Element = document.body) {
  const event = new KeyboardEvent("keydown", { key, bubbles: true, ...init });
  Object.defineProperty(event, "target", { value: target });
  return event;
}

describe("stepThrough", () => {
  const ids = ["a", "b", "c"];

  it("moves to the next and previous conversation", () => {
    expect(stepThrough(ids, "b", 1)).toBe("c");
    expect(stepThrough(ids, "b", -1)).toBe("a");
  });

  it("stops at the ends of the list", () => {
    expect(stepThrough(ids, "c", 1)).toBe("c");
    expect(stepThrough(ids, "a", -1)).toBe("a");
  });

  it("starts at the top, or the bottom going up, when nothing in the list is open", () => {
    expect(stepThrough(ids, null, 1)).toBe("a");
    expect(stepThrough(ids, "gone", -1)).toBe("c");
    expect(stepThrough([], null, 1)).toBeNull();
  });
});

describe("shortcutFor", () => {
  it("maps arrows and j/k to moving through the list", () => {
    expect(shortcutFor(press("ArrowDown"), true)).toBe("next");
    expect(shortcutFor(press("j"), true)).toBe("next");
    expect(shortcutFor(press("ArrowUp"), true)).toBe("prev");
    expect(shortcutFor(press("k"), true)).toBe("prev");
    expect(shortcutFor(press("?", { shiftKey: true }), true)).toBe("help");
  });

  it("gives accept and reply to agents only", () => {
    expect(shortcutFor(press("a"), true)).toBe("accept");
    expect(shortcutFor(press("r"), true)).toBe("reply");
    expect(shortcutFor(press("a"), false)).toBeNull();
    expect(shortcutFor(press("r"), false)).toBeNull();
    expect(shortcutFor(press("j"), false)).toBe("next");
  });

  it("ignores keys with a modifier or while typing; Escape only leaves the field", () => {
    expect(shortcutFor(press("j", { ctrlKey: true }), true)).toBeNull();
    expect(shortcutFor(press("r", { metaKey: true }), true)).toBeNull();
    const box = document.body.appendChild(document.createElement("textarea"));
    expect(shortcutFor(press("j", {}, box), true)).toBeNull();
    expect(shortcutFor(press("Escape", {}, box), true)).toBe("leave");
  });

  it("leaves keys to an open menu or dialog", () => {
    document.body.innerHTML = '<button aria-haspopup="menu" aria-expanded="true"></button>';
    expect(shortcutFor(press("Escape"), true)).toBeNull();
    document.body.innerHTML = "<dialog open></dialog>";
    expect(shortcutFor(press("j"), true)).toBeNull();
  });
});
