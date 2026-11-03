import { describe, expect, it } from "vitest";
import { ApiError } from "../lib/api";
import { failureReason } from "./useChat";

describe("failureReason", () => {
  it("tells the customer why a message didn't go", () => {
    expect(failureReason(new ApiError(429, "RATE_LIMITED", "x"))).toBe("You're sending too fast. Try again in a moment.");
    expect(failureReason(new ApiError(422, "MESSAGE_TOO_LONG", "x"))).toBe("That message is too long.");
    expect(failureReason(new TypeError("Failed to fetch"))).toBe("No connection.");
    expect(failureReason(new ApiError(500, "UNKNOWN", "x"))).toBe("Not sent.");
  });
});
