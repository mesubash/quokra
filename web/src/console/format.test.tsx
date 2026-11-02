import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ago, eventLabel, highlight, reasonLabel, toneOf } from "./format";

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

  it("gives the same customer the same avatar colour", () => {
    expect(toneOf("Pat Example")).toBe(toneOf("Pat Example"));
    expect(toneOf("c0ffee00-0000-4000-8000-000000000001")).toBeGreaterThanOrEqual(0);
    const tones = new Set(["Pat", "Sam", "Alex", "Robin", "Kim", "Lee", "Max", "Jo"].map(toneOf));
    expect([...tones].every((t) => t >= 0 && t < 8)).toBe(true);
    expect(tones.size).toBeGreaterThan(1);
  });

  it("marks what the search matched, ignoring case", () => {
    const { container } = render(<p>{highlight("Refund for order 12", "ORDER")}</p>);
    expect(container.innerHTML).toBe("<p>Refund for <mark>order</mark> 12</p>");
  });

  it("treats the search as plain text, not a pattern", () => {
    const { container } = render(<p>{highlight("Is it 5.00 (or more)?", "(or")}</p>);
    expect(container.innerHTML).toBe("<p>Is it 5.00 <mark>(or</mark> more)?</p>");
    expect(highlight("hello", "")).toBe("hello");
  });
});
