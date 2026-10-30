import { describe, expect, it } from "vitest";
import { setTheme } from "./theme";

describe("setTheme", () => {
  it("switches between light and dark and ignores anything else", () => {
    setTheme("dark");
    expect(document.documentElement.dataset.theme).toBe("dark");

    setTheme("purple");
    expect(document.documentElement.dataset.theme).toBe("dark");

    setTheme("light");
    expect(document.documentElement.dataset.theme).toBe("light");
  });
});
