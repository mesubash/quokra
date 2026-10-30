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
  texts: {},
};

describe("fromDraft", () => {
  it("splits keywords and gives new questions unique ids", () => {
    const out = fromDraft({
      bot,
      menu: [
        { id: "hours", title: "Hours", keywords: "open, , closing time ", answer: "9 to 5" },
        { id: "", title: "Hours!", keywords: "", answer: "Again" },
        { id: "", title: "Talk to a human?", keywords: "", answer: "x" },
        { id: "", title: "???", keywords: "", answer: "y" },
      ],
      humanKeywords: "person, agent",
      urgentKeywords: "",
    });

    expect(out.menu.map((m) => m.id)).toEqual(["hours", "hours-2", "talk-to-a-human", "question-4"]);
    expect(out.menu[0].keywords).toEqual(["open", "closing time"]);
    expect(out.human.keywords).toEqual(["person", "agent"]);
    expect(out.urgent.keywords).toEqual([]);
  });
});
