import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { linkify } from "./links";

function links(text: string) {
  const { container } = render(<p>{linkify(text)}</p>);
  return [...container.querySelectorAll("a")].map((a) => [a.textContent, a.getAttribute("href")]);
}

describe("linkify", () => {
  it("links full and www addresses", () => {
    expect(links("see https://example.com/orders?id=4 now")).toEqual([["https://example.com/orders?id=4", "https://example.com/orders?id=4"]]);
    expect(links("www.example.org")).toEqual([["www.example.org", "https://www.example.org"]]);
  });

  it("links plain addresses with a known ending", () => {
    expect(links("visit example.co.uk/help.")).toEqual([["example.co.uk/help", "https://example.co.uk/help"]]);
    expect(links("(example.org)")).toEqual([["example.org", "https://example.org"]]);
    expect(links("go to Example.COM, thanks")).toEqual([["Example.COM", "https://Example.COM"]]);
  });

  it("leaves everything else as text", () => {
    expect(links("e.g. version 1.2, file.txt, Mr.Smith, 3.5km, hello.world")).toEqual([]);
    expect(links("mail me at pat@example.test or pat@gmail.com")).toEqual([]);
    expect(links("javascript:alert(1)")).toEqual([]);
  });

  it("shows labels on the bot's links only", () => {
    const text = "Terms: [Help centre](https://www.example.com/help/terms) or www.example.org";
    const { container } = render(<p>{linkify(text, { labels: true })}</p>);
    expect([...container.querySelectorAll("a")].map((a) => [a.textContent, a.getAttribute("href")])).toEqual([
      ["Help centre ↗", "https://www.example.com/help/terms"],
      ["www.example.org", "https://www.example.org"],
    ]);
    expect(links("[Safe](https://evil.test)")).toEqual([["https://evil.test", "https://evil.test"]]);
    expect(links("[x](javascript:alert(1))")).toEqual([]);
  });

  it("keeps the text around links", () => {
    const { container } = render(<p>{linkify("a example.net b")}</p>);
    expect(container.textContent).toBe("a example.net b");
  });
});
