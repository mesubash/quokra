// Messages between the panel (this iframe) and sdk.js on the host page.

const SOURCE = "quokra";

export type HostMessage = { type: "chat.open" } | { type: "chat.close" };

export const embedded = window.parent !== window;

// Only non-sensitive data (colour, unread count) goes to the host, so "*" is fine as the target.
// Which sites can embed the panel at all is limited by frame-ancestors on the server.
export function postToHost(message: { type: string; [key: string]: unknown }) {
  if (embedded) window.parent.postMessage({ source: SOURCE, ...message }, "*");
}

export function onHostMessage(handler: (message: HostMessage) => void): () => void {
  const listener = (event: MessageEvent) => {
    if (event.source !== window.parent || event.data?.source !== SOURCE) return;
    if (event.data.type === "chat.open" || event.data.type === "chat.close") handler(event.data as HostMessage);
  };
  window.addEventListener("message", listener);
  return () => window.removeEventListener("message", listener);
}
