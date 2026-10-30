import { describe, expect, it } from "vitest";
import { ago, eventLabel, reasonLabel } from "./format";

describe("format", () => {
  it("names who closed a chat", () => {
    expect(eventLabel("RESOLVED", "Sam", null)).toBe("Sam resolved");
    expect(eventLabel("RESOLVED", null, "AUTO")).toBe("Closed automatically (no reply)");
    expect(eventLabel("RESOLVED", null, "CUSTOMER")).toBe("Customer started a new chat");
  });

  it("says how long ago, roughly", () => {
    const minutesAgo = (m: number) => new Date(Date.now() - m * 60000).toISOString();
    expect(ago(minutesAgo(0))).toBe("now");
    expect(ago(minutesAgo(5))).toBe("5 min");
    expect(ago(minutesAgo(120))).toBe("2 h");
    expect(ago(null)).toBe("");
  });

  it("explains handoff reasons in plain words", () => {
    expect(reasonLabel("FALLBACK_LIMIT")).toBe("Bot didn't understand");
  });
});
