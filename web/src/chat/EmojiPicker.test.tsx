import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Composer } from "./Composer";

afterEach(cleanup);

describe("emoji button", () => {
  it("puts the emoji where the cursor is", () => {
    const onSend = vi.fn();
    render(<Composer onSend={onSend} disabled={false} />);
    const input = screen.getByLabelText("Message") as HTMLTextAreaElement;
    fireEvent.change(input, { target: { value: "Thanks !" } });
    input.setSelectionRange(7, 7);

    fireEvent.click(screen.getByLabelText("Add an emoji"));
    fireEvent.click(screen.getByRole("tab", { name: "Gestures" }));
    fireEvent.click(screen.getByLabelText("🙏"));

    expect(input.value).toBe("Thanks 🙏!");
    expect(screen.queryByRole("dialog", { name: "Emoji" })).toBeNull();
  });

  it("remembers recent picks in their own tab", () => {
    localStorage.removeItem("quokra.recent-emoji");
    render(<Composer onSend={() => {}} disabled={false} />);
    fireEvent.click(screen.getByLabelText("Add an emoji"));
    fireEvent.click(screen.getByLabelText("😀"));
    fireEvent.click(screen.getByLabelText("Add an emoji"));

    expect(screen.getByRole("tab", { name: "Recent" }).getAttribute("aria-selected")).toBe("true");
    expect(screen.getByRole("tabpanel", { name: "Recent" }).textContent).toBe("😀");
  });

  it("isn't offered while the chat asks for an email or name", () => {
    render(<Composer onSend={() => {}} disabled={false} asking="EMAIL" />);
    expect(screen.queryByLabelText("Add an emoji")).toBeNull();
  });
});
