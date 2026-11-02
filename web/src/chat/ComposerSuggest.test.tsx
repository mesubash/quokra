import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { suggestionNote } from "../console/ConversationView";
import { Composer } from "./Composer";

afterEach(cleanup);

describe("Composer suggestions", () => {
  it("puts the suggestion in the box to edit, with where it came from", async () => {
    const onSend = vi.fn();
    const onSuggest = vi.fn(() => Promise.resolve({ text: "Returns take 30 days.", note: "Suggested from Handbook › Returns." }));
    render(<Composer onSend={onSend} disabled={false} onSuggest={onSuggest} />);

    fireEvent.click(screen.getByRole("button", { name: "Suggest a reply" }));

    await waitFor(() => expect(screen.getByDisplayValue("Returns take 30 days.")).toBeTruthy());
    expect(screen.getByText("Suggested from Handbook › Returns.")).toBeTruthy();
    // Nothing is sent until the agent sends it.
    expect(onSend).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Send" }));
    expect(onSend).toHaveBeenCalledWith("Returns take 30 days.");
    expect(screen.queryByText("Suggested from Handbook › Returns.")).toBeNull();
  });

  it("keeps what's typed when there's no suggestion", async () => {
    render(
      <Composer
        onSend={vi.fn()}
        disabled={false}
        onSuggest={() => Promise.resolve({ text: null, note: "Nothing in your documents answers this." })}
      />,
    );
    fireEvent.change(screen.getByLabelText("Message"), { target: { value: "Let me check" } });
    fireEvent.click(screen.getByRole("button", { name: "Suggest a reply" }));

    expect(await screen.findByText("Nothing in your documents answers this.")).toBeTruthy();
    expect(screen.getByDisplayValue("Let me check")).toBeTruthy();
  });

  it("has no button without AI", () => {
    render(<Composer onSend={vi.fn()} disabled={false} />);
    expect(screen.queryByRole("button", { name: "Suggest a reply" })).toBeNull();
  });
});

describe("suggestionNote", () => {
  it("names the sources, or says why there's nothing", () => {
    expect(
      suggestionNote({
        text: "Yes.",
        sources: [
          { title: "Handbook", heading: "Returns" },
          { title: "FAQ", heading: null },
        ],
        reason: null,
      }),
    ).toBe("Suggested from Handbook › Returns, FAQ. Check it before sending.");
    expect(suggestionNote({ text: null, sources: [], reason: "NO_MATCH" })).toBe("Nothing in your documents answers this.");
    expect(suggestionNote({ text: null, sources: [], reason: "AI_UNAVAILABLE" })).toContain("Try again");
  });
});
