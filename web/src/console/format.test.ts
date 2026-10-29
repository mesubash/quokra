import { describe, expect, it } from "vitest";
import { ago, reasonLabel } from "./format";

describe("format", () => {
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
