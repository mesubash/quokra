// Messages between the panel (this iframe) and sdk.js on the host page.

const SOURCE = "quokra";

export type HostMessage =
  | { type: "chat.open" }
  | { type: "chat.close" }
  | { type: "chat.identify"; token: string }
  | { type: "chat.logout" }
  | { type: "theme.set"; theme: string }
  // From sdk.js to the console: whether the dashboard can show browser notifications, and the permission.
  | { type: "console.notifications"; available: boolean; permission: string }
  // From sdk.js to the console: an agent clicked a notification; show that conversation.
  | { type: "console.open"; conversationId: string }
  | { type: "auth.token"; token: string };

export const embedded = window.parent !== window;

// Messages go only to the page that embedded us: sdk.js passes its origin as ?host=. Which sites may embed
// the panel at all is limited by frame-ancestors on the server. Without ?host= (an older sdk.js) they go to
// any parent, as before.
const HOST = (() => {
  const host = new URLSearchParams(window.location.search).get("host");
  return host && /^https?:\/\/[^/]+$/.test(host) ? host : "*";
})();

export function postToHost(message: { type: string; [key: string]: unknown }) {
  if (embedded) window.parent.postMessage({ source: SOURCE, ...message }, HOST);
}

export function onHostMessage(handler: (message: HostMessage) => void): () => void {
  const listener = (event: MessageEvent) => {
    if (event.source !== window.parent || event.data?.source !== SOURCE) return;
    const { type } = event.data;
    if (type === "chat.open" || type === "chat.close" || type === "chat.logout") handler(event.data as HostMessage);
    else if (type === "theme.set" && typeof event.data.theme === "string") handler(event.data as HostMessage);
    else if (
      type === "console.notifications" &&
      typeof event.data.available === "boolean" &&
      typeof event.data.permission === "string"
    ) {
      handler(event.data as HostMessage);
    } else if (type === "console.open" && typeof event.data.conversationId === "string") {
      handler(event.data as HostMessage);
    }
    // Only our parent can send a token, and frame-ancestors already limits who the parent can be.
    else if ((type === "auth.token" || type === "chat.identify") && typeof event.data.token === "string") {
      handler(event.data as HostMessage);
    }
  };
  window.addEventListener("message", listener);
  return () => window.removeEventListener("message", listener);
}
