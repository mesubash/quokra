import { widgetApi, type Session } from "../lib/api";

const storageKey = (pk: string) => `quokra.session.${pk}`;

function stored(pk: string): Session | null {
  try {
    const raw = localStorage.getItem(storageKey(pk));
    if (!raw) return null;
    const session = JSON.parse(raw) as Session;
    return new Date(session.expiresAt) > new Date() ? session : null;
  } catch {
    return null;
  }
}

// Drops a stored session the server no longer accepts (expired, or the server's key changed).
export function forgetSession(pk: string) {
  try {
    localStorage.removeItem(storageKey(pk));
  } catch {
    // Nothing stored if storage is blocked.
  }
}

// Reuses the visitor's session if there is a valid one, otherwise starts a new one.
export async function getSession(pk: string): Promise<Session> {
  const existing = stored(pk);
  if (existing) return existing;

  const session = await widgetApi.startSession(pk);
  try {
    localStorage.setItem(storageKey(pk), JSON.stringify(session));
  } catch {
    // Storage can be blocked in some iframes; the session then lasts for this page only.
  }
  return session;
}
