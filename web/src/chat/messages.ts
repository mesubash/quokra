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
