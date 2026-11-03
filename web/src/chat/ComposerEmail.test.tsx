import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { Composer } from "./Composer";

afterEach(cleanup);

function typeAndSend(email: string) {
  fireEvent.change(screen.getByLabelText("Your email"), { target: { value: email } });
  fireEvent.click(screen.getByRole("button", { name: "Send" }));
}

it("offers the fix for a mistyped email, and sends what the customer picks", () => {
  const onSend = vi.fn();
  render(<Composer onSend={onSend} disabled={false} asking="EMAIL" />);

  typeAndSend("pat@gmial.com");
  expect(onSend).not.toHaveBeenCalled();
  expect(screen.getByText("pat@gmail.com")).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "Yes, use it" }));
  expect(onSend).toHaveBeenLastCalledWith("pat@gmail.com");

  typeAndSend("pat@gmial.com");
  fireEvent.click(screen.getByRole("button", { name: "No, keep mine" }));
  expect(onSend).toHaveBeenLastCalledWith("pat@gmial.com");
});

it("sends a good email straight away, and never questions other messages", () => {
  const onSend = vi.fn();
  const { rerender } = render(<Composer onSend={onSend} disabled={false} asking="EMAIL" />);
  typeAndSend("pat@gmail.com");
  expect(onSend).toHaveBeenLastCalledWith("pat@gmail.com");

  rerender(<Composer onSend={onSend} disabled={false} />);
  fireEvent.change(screen.getByLabelText("Message"), { target: { value: "write to pat@gmial.com" } });
  fireEvent.click(screen.getByRole("button", { name: "Send" }));
  expect(onSend).toHaveBeenLastCalledWith("write to pat@gmial.com");
});
