import { act, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { TYPING_SHOWN_MS, useTypingSignal } from "./typing";

afterEach(() => vi.useRealTimers());

describe("useTypingSignal", () => {
  it("shows who is typing until they stop for a while", () => {
    vi.useFakeTimers();
    const { result } = renderHook(() => useTypingSignal());

    act(() => result.current.seen({ senderType: "AGENT", name: "Sam" }));
    expect(result.current.who?.name).toBe("Sam");

    act(() => vi.advanceTimersByTime(TYPING_SHOWN_MS - 1000));
    act(() => result.current.seen({ senderType: "AGENT", name: "Sam" }));
    act(() => vi.advanceTimersByTime(TYPING_SHOWN_MS - 1000));
    expect(result.current.who).not.toBeNull();

    act(() => vi.advanceTimersByTime(1000));
    expect(result.current.who).toBeNull();
  });

  it("clears at once when their message arrives", () => {
    const { result } = renderHook(() => useTypingSignal());
    act(() => result.current.seen({ senderType: "CUSTOMER", name: null }));
    act(() => result.current.clear());
    expect(result.current.who).toBeNull();
  });
});
