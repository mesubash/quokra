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
