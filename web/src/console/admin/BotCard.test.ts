import { describe, expect, it } from "vitest";
import type { BotFields } from "../../lib/api";
import { fromDraft } from "./BotCard";

const bot: BotFields = {
  greeting: "Hi",
  menu: [],
  human: { title: "Talk to a person", keywords: [] },
  urgent: { keywords: [], message: "Connecting you now" },
  fallback: "Sorry?",
  max_fallbacks: 2,
  handoff_message: "Connecting you",
  texts: { online: "Online", waiting: "  " },
};

const item = { id: "", title: "", keywords: "", answer: "", followUps: [] as string[], inMenu: true };

describe("fromDraft", () => {
  it("splits keywords and gives new questions unique ids", () => {
    const out = fromDraft({
      bot,
      menu: [
        { ...item, id: "hours", title: "Hours", keywords: "open, , closing time ", answer: "9 to 5" },
        { ...item, title: "Hours!", answer: "Again" },
        { ...item, title: "Talk to a human?", answer: "x" },
        { ...item, title: "???", answer: "y" },
      ],
      humanKeywords: "person, agent",
      urgentKeywords: "",
    });

    expect(out.menu.map((m) => m.id)).toEqual(["hours", "hours-2", "talk-to-a-human", "question-4"]);
    expect(out.menu[0].keywords).toEqual(["open", "closing time"]);
    expect(out.human.keywords).toEqual(["person", "agent"]);
    expect(out.urgent.keywords).toEqual([]);
    expect(out.texts).toEqual({ online: "Online" });
  });

  it("keeps follow-ups to questions that are still there, never to itself", () => {
    const out = fromDraft({
      bot,
      menu: [
        { ...item, id: "device", title: "Device", answer: "a", followUps: ["sos", "gone", "device"] },
        { ...item, id: "sos", title: "SOS", answer: "b", inMenu: false },
      ],
      humanKeywords: "",
      urgentKeywords: "",
    });

    expect(out.menu[0].follow_ups).toEqual(["sos"]);
    expect(out.menu[1].in_menu).toBe(false);
  });
});
