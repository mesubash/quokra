import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { AgentApi } from "../lib/api";
import { usePresence } from "./PresenceMenu";

describe("usePresence", () => {
  it("checks in on start and can switch to away", async () => {
    const presence = vi.fn((status?: string) => Promise.resolve({ status: status ?? "ONLINE" }));
    const api = { presence } as unknown as AgentApi;
    const { result } = renderHook(() => usePresence(api, true));

    await waitFor(() => expect(result.current.status).toBe("ONLINE"));
    expect(presence).toHaveBeenCalledWith();

    await act(() => result.current.change("AWAY"));
    expect(presence).toHaveBeenLastCalledWith("AWAY");
    expect(result.current.status).toBe("AWAY");
  });

  it("does nothing for admins who don't answer chats", () => {
    const presence = vi.fn();
    renderHook(() => usePresence({ presence } as unknown as AgentApi, false));
    expect(presence).not.toHaveBeenCalled();
  });
});
