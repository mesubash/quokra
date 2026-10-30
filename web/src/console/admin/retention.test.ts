import { describe, expect, it } from "vitest";
import { retentionChoices } from "./AdminArea";

describe("retentionChoices", () => {
  it("offers only what the server allows", () => {
    expect(retentionChoices({ minMonths: 3, maxMonths: 24 })).toEqual([3, 6, 12, 24]);
    expect(retentionChoices({ minMonths: 1, maxMonths: 0 })).toEqual([1, 3, 6, 12, 24, 36, 60, 0]);
  });

  it("keeps an allowed value set by hand, but not one outside the limits", () => {
    expect(retentionChoices({ minMonths: 1, maxMonths: 24 }, 18)).toContain(18);
    expect(retentionChoices({ minMonths: 1, maxMonths: 24 }, 48)).not.toContain(48);
  });
});
