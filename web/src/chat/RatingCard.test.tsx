import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, type Conversation } from "../lib/api";
import { RatingCard } from "./RatingCard";

afterEach(cleanup);

const closed: Conversation = {
  id: "c1",
  status: "RESOLVED",
  assignee: { name: "Sam" },
  asking: null,
  createdAt: "2026-10-29T10:00:00Z",
  lastMessageAt: "2026-10-29T10:05:00Z",
  resolvedAt: "2026-10-29T10:05:00Z",
  canRate: true,
  rating: null,
};

describe("RatingCard", () => {
  it("asks for a thumb, then a comment, then says thanks", async () => {
    const rate = vi.fn(() => Promise.resolve());
    render(<RatingCard conversation={closed} rate={rate} />);
    expect(screen.getByText("How was your chat with Sam?")).toBeTruthy();

    fireEvent.click(screen.getByLabelText("Good"));
    const box = await screen.findByLabelText("Thanks! Anything to add?");
    expect(rate).toHaveBeenCalledWith(true, undefined);

    fireEvent.change(box, { target: { value: " Quick answer " } });
    fireEvent.click(screen.getByText("Send"));
    expect(await screen.findByText(/Thanks for your feedback/)).toBeTruthy();
    expect(rate).toHaveBeenLastCalledWith(true, "Quick answer");
  });

  it("skips the comment, and shows thanks for a chat rated before", async () => {
    render(<RatingCard conversation={closed} rate={() => Promise.resolve()} />);
    fireEvent.click(screen.getByLabelText("Not good"));
    fireEvent.click(await screen.findByText("No thanks"));
    expect(screen.getByText(/Thanks for your feedback/)).toBeTruthy();
    cleanup();

    render(<RatingCard conversation={{ ...closed, canRate: false, rating: { positive: true, comment: null } }} rate={vi.fn()} />);
    expect(screen.getByText(/Thanks for your feedback/)).toBeTruthy();
  });

  it("isn't shown for open chats or chats only the bot had", () => {
    const { container, rerender } = render(<RatingCard conversation={{ ...closed, status: "HUMAN" }} rate={vi.fn()} />);
    expect(container.innerHTML).toBe("");
    rerender(<RatingCard conversation={{ ...closed, canRate: false }} rate={vi.fn()} />);
    expect(container.innerHTML).toBe("");
  });

  it("treats already rated as done and other failures as retryable", async () => {
    const rate = vi
      .fn()
      .mockRejectedValueOnce(new ApiError(500, "UNKNOWN", "Oops"))
      .mockRejectedValueOnce(new ApiError(409, "ALREADY_RATED", "Rated"));
    render(<RatingCard conversation={closed} rate={rate} />);

    fireEvent.click(screen.getByLabelText("Good"));
    expect(await screen.findByRole("alert")).toBeTruthy();
    fireEvent.click(screen.getByLabelText("Good"));
    expect(await screen.findByText(/Thanks for your feedback/)).toBeTruthy();
  });
});
