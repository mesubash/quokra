import type { HandoffReason, Priority } from "../lib/api";

export function ago(iso: string | null) {
  if (!iso) return "";
  const minutes = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60000));
  if (minutes < 1) return "now";
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.round(minutes / 60);
  return hours < 24 ? `${hours} h` : `${Math.round(hours / 24)} d`;
}

export function priorityLabel(priority: Priority) {
  return priority === "URGENT" ? "Urgent" : priority === "HIGH" ? "High" : "Normal";
}

export function reasonLabel(reason: HandoffReason) {
  switch (reason) {
    case "CUSTOMER_REQUEST":
      return "Asked for a person";
    case "FALLBACK_LIMIT":
      return "Bot didn't understand";
    case "URGENT":
      return "Urgent keyword";
    case "BOT_ERROR":
      return "Bot error";
  }
}

export function initials(name: string | null) {
  if (!name) return "?";
  return name.split(/\s+/).filter(Boolean).map((p) => p[0]).join("").slice(0, 2).toUpperCase();
}

export function dateTime(iso: string | null) {
  if (!iso) return "";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "" : d.toLocaleString([], { dateStyle: "medium", timeStyle: "short" });
}

// One line for the activity list, e.g. "Sam accepted".
export function eventLabel(type: string, actorName: string | null, reason: string | null) {
  const who = actorName ?? "Customer";
  switch (type) {
    case "CREATED":
      return "Chat started";
    case "CONTACT_ADDED":
      return "Left their email";
    case "HANDOFF":
      return "Handed to the team";
    case "ACCEPTED":
      return `${who} accepted`;
    case "RESOLVED":
      return `${who} resolved`;
    case "RETURNED_TO_BOT":
      return `${who} handed back to the bot`;
    case "REOPENED":
      return actorName ? `${actorName} reopened` : "Customer wrote again";
    case "TRANSFERRED":
      return reason ? `${who} transferred to ${reason}` : `${who} put it back in the queue`;
    case "BLOCKED":
      return `${who} blocked the contact`;
    default:
      return type.toLowerCase().replace(/_/g, " ");
  }
}

// Compact time for lists: "11:00 AM" today, "Yesterday, 11:00 AM", otherwise "Oct 28, 11:00 AM".
export function shortTime(iso: string | null, now = new Date()) {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  const time = d.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
  const day = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const days = Math.round((day(now) - day(d)) / 86_400_000);
  if (days === 0) return time;
  if (days === 1) return `Yesterday, ${time}`;
  const sameYear = d.getFullYear() === now.getFullYear();
  return `${d.toLocaleDateString([], { month: "short", day: "numeric", year: sameYear ? undefined : "numeric" })}, ${time}`;
}

// What kind of step an activity line is, for its dot colour.
export function eventTone(type: string) {
  switch (type) {
    case "HANDOFF":
      return "warn";
    case "ACCEPTED":
    case "TRANSFERRED":
    case "REOPENED":
      return "accent";
    case "RESOLVED":
      return "success";
    case "BLOCKED":
      return "danger";
    default:
      return "muted";
  }
}
