import { describe, expect, it } from "vitest";
import { matchReplies } from "./Composer";

const replies = [{ title: "Refunds" }, { title: "Opening hours" }];

describe("matchReplies", () => {
  it("lists everything for a bare slash and filters by title", () => {
    expect(matchReplies("/", replies)).toHaveLength(2);
    expect(matchReplies("/HOUR", replies)).toEqual([{ title: "Opening hours" }]);
  });

  it("stays out of the way of normal text", () => {
    expect(matchReplies("refunds", replies)).toEqual([]);
    expect(matchReplies("/ref\nmore", replies)).toEqual([]);
  });
});
