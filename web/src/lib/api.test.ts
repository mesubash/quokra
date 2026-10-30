import { afterEach, describe, expect, it, vi } from "vitest";
import { agentApi } from "./api";

afterEach(() => vi.unstubAllGlobals());

describe("agent calls", () => {
  it("retry once with a fresh token after a 401", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ error: { code: "UNAUTHORIZED" } }), { status: 401 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ waiting: 1, mine: 0, myUnread: 0 }), { status: 200 }));
    vi.stubGlobal("fetch", fetch);
    const refresh = vi.fn(() => Promise.resolve("new-token"));

    const counts = await agentApi(() => "old-token", refresh).counts();

    expect(counts.waiting).toBe(1);
    expect(refresh).toHaveBeenCalledOnce();
    expect(fetch.mock.calls[1][1].headers.Authorization).toBe("Bearer new-token");
  });
});
