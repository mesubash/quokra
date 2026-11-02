import { describe, expect, it } from "vitest";
import type { Conversation, Tenant } from "../lib/api";
import { canEmailTranscript } from "./ChatPanel";

const tenant = (transcripts?: boolean) => ({ transcripts }) as Tenant;
const chat = (hasEmail?: boolean) => ({ status: "HUMAN", hasEmail }) as Conversation;

describe("canEmailTranscript", () => {
  it("shows only when email works and the customer gave one", () => {
    expect(canEmailTranscript(chat(true), tenant(true))).toBe(true);
    expect(canEmailTranscript(chat(false), tenant(true))).toBe(false);
    expect(canEmailTranscript(chat(true), tenant(false))).toBe(false);
    expect(canEmailTranscript(chat(true), tenant(undefined))).toBe(false);
    expect(canEmailTranscript(null, tenant(true))).toBe(false);
  });
});
