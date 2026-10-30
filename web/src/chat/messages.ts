import type { Message } from "../lib/api";

// Adds new messages, skipping ones we already have. Ids are UUIDv7, so sorting them sorts by time.
export function mergeMessages(current: Message[], incoming: Message[]): Message[] {
  const known = new Set(current.map((m) => m.id));
  const added = incoming.filter((m) => !known.has(m.id));
  if (added.length === 0) return current;
  return [...current, ...added].sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
}

// Menu buttons are only offered under the latest bot message.
export function activeOptions(messages: Message[]) {
  const last = messages[messages.length - 1];
  return last?.senderType === "BOT" ? (last.options ?? []) : [];
}

// Messages from the bot, agents or the system that arrived after `seenUpTo`.
export function countUnread(messages: Message[], seenUpTo: string | null) {
  if (seenUpTo === null) return 0;
  return messages.filter((m) => m.senderType !== "CUSTOMER" && m.id > seenUpTo).length;
}

// A label for the divider between days in a chat: "Today", "Yesterday", or "Monday, January 15".
export function dayLabel(iso: string, now = new Date()) {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  const day = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const days = Math.round((day(now) - day(d)) / 86_400_000);
  if (days === 0) return "Today";
  if (days === 1) return "Yesterday";
  return d.toLocaleDateString([], {
    weekday: "long",
    month: "long",
    day: "numeric",
    year: d.getFullYear() === now.getFullYear() ? undefined : "numeric",
  });
}

export function dayKey(iso: string) {
  const d = new Date(iso);
  return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
}
