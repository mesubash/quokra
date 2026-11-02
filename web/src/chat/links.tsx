import type { ReactNode } from "react";

// Web addresses in message text become links: with http(s):// or www., or a plain address like
// example.co.uk/help when its ending is a real one from the list below. The list keeps "e.g.", "v1.2" or
// "file.txt" from turning into links. Emails stay text. Links only ever open as http(s), in a new tab.

const ENDINGS = [
  "com", "org", "net", "edu", "gov", "io", "co", "info", "app", "dev", "travel", "biz", "me", "ai",
  "np", "in", "uk", "us", "au", "ca", "de", "fr", "cn", "jp", "ch", "nl", "eu",
].join("|");

// One pass over the text: an explicit link, or a plain address ending in one of ENDINGS.
const LINK = new RegExp(
  String.raw`\b(?:https?:\/\/|www\.)[^\s<>"]+` +
    String.raw`|(?<![@\w.-])(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+(?:${ENDINGS})\b(?![@\w-])(?:[/?#][^\s<>"]*)?`,
  "gi",
);

// Punctuation that ends a sentence rather than the address: "see example.net." or "(example.net)".
const TRAILING = /[.,;:!?'")\]]+$/;

export function linkify(text: string): ReactNode[] {
  const out: ReactNode[] = [];
  let at = 0;
  for (const match of text.matchAll(LINK)) {
    let raw = match[0];
    const trail = raw.match(TRAILING)?.[0] ?? "";
    // Keep a closing bracket that belongs to the address, e.g. a wiki link "…_(film)".
    const keep = trail.startsWith(")") && raw.includes("(") ? 0 : trail.length;
    raw = raw.slice(0, raw.length - keep);
    const start = match.index;
    if (start > at) out.push(text.slice(at, start));
    const href = /^https?:\/\//i.test(raw) ? raw : `https://${raw}`;
    out.push(
      <a key={start} href={href} target="_blank" rel="noopener noreferrer nofollow">
        {raw}
      </a>,
    );
    at = start + raw.length;
  }
  if (at < text.length) out.push(text.slice(at));
  return out;
}
