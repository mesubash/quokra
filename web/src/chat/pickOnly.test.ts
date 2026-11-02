import { describe, expect, it } from "vitest";
import type { Conversation, Tenant } from "../lib/api";
import { pickOnly } from "./ChatPanel";

const tenant = (freeText?: boolean) => ({ freeText }) as Tenant;
const chat = (status: Conversation["status"], asking: Conversation["asking"] = null) =>
  ({ status, asking }) as Conversation;

describe("pickOnly", () => {
  it("hides the box only while the bot answers and the company turned typing off", () => {
    expect(pickOnly(chat("BOT"), tenant(false))).toBe(true);
    expect(pickOnly(chat("BOT"), tenant(true))).toBe(false);
    expect(pickOnly(chat("BOT"), tenant(undefined))).toBe(false);
  });

  it("still lets customers type their email and name, and to the team", () => {
    expect(pickOnly(chat("BOT", "EMAIL"), tenant(false))).toBe(false);
    expect(pickOnly(chat("BOT", "NAME"), tenant(false))).toBe(false);
    expect(pickOnly(chat("WAITING"), tenant(false))).toBe(false);
    expect(pickOnly(chat("HUMAN"), tenant(false))).toBe(false);
  });
});
