import { describe, expect, it } from "vitest";
import { textOn } from "./color";

describe("textOn", () => {
  it("is white on mid and dark brand colours, orange included", () => {
    for (const c of ["#D66700", "#0051fb", "#cf222e", "#0a7c66"]) expect(textOn(c)).toBe("#fff");
  });

  it("is dark only on light colours", () => {
    for (const c of ["#ffd400", "#a5d8ff", "#ffffff"]) expect(textOn(c)).toBe("#1f2328");
  });
});
