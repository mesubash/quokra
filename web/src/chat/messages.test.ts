import { describe, expect, it } from "vitest";
import type { Message } from "../lib/api";
import { activeOptions, countUnread, mergeMessages } from "./messages";

function message(id: string, senderType: Message["senderType"] = "BOT", options: Message["options"] = null): Message {
  return { id, senderType, senderName: null, body: id, options, clientMessageId: null, createdAt: "" };
}

describe("mergeMessages", () => {
  it("adds new messages in id order", () => {
    const merged = mergeMessages([message("01-a"), message("03-c")], [message("02-b")]);
    expect(merged.map((m) => m.id)).toEqual(["01-a", "02-b", "03-c"]);
  });

  it("skips messages it already has", () => {
    const current = [message("01-a")];
    expect(mergeMessages(current, [message("01-a")])).toBe(current);
  });
});

describe("activeOptions", () => {
  const menu = [{ id: "pricing", title: "Pricing" }];

  it("offers the buttons of the latest bot message", () => {
    expect(activeOptions([message("01", "BOT", menu)])).toEqual(menu);
  });

  it("offers nothing once the customer has replied", () => {
    expect(activeOptions([message("01", "BOT", menu), message("02", "CUSTOMER")])).toEqual([]);
  });

  it("offers nothing for an empty chat", () => {
    expect(activeOptions([])).toEqual([]);
  });
});

describe("countUnread", () => {
  const history = [message("01", "BOT"), message("02", "CUSTOMER"), message("03", "BOT"), message("04", "SYSTEM")];

  it("counts replies after the last seen message", () => {
    expect(countUnread(history, "02")).toBe(2);
  });

  it("ignores the customer's own messages", () => {
    expect(countUnread([...history, message("05", "CUSTOMER")], "04")).toBe(0);
  });

  it("is zero before anything has been seen", () => {
    expect(countUnread(history, null)).toBe(0);
  });
});
