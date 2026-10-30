import { useCallback, useEffect, useRef, useState } from "react";
import { devApi } from "../lib/api";
import { embedded, onHostMessage, postToHost } from "../lib/bridge";
import { ConsoleApp } from "./ConsoleApp";

const TOKEN_KEY = "quokra.agentToken";
const REFRESH_BEFORE_MS = 60_000;
// How long to wait for the dashboard to send a token before saying so.
const SIGN_IN_TIMEOUT_MS = 10_000;

// The agent console. Embedded in a dashboard (via sdk.js), the dashboard provides the agent's
// token; opened on its own, local development can sign in as a dev agent.
export function Console() {
  return embedded ? <EmbeddedConsole /> : <StandaloneConsole />;
}

// Milliseconds until the token expires, or null if it can't be read.
export function expiresIn(token: string): number | null {
  try {
    const payload = JSON.parse(atob(token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")));
    return typeof payload.exp === "number" ? payload.exp * 1000 - Date.now() : null;
  } catch {
    return null;
  }
}

function EmbeddedConsole() {
  const [token, setToken] = useState<string | null>(null);
  const tokenRef = useRef<string | null>(null);
  const getToken = useCallback(() => tokenRef.current ?? "", []);
  const params = new URLSearchParams(window.location.search);
  const closable = params.get("mode") === "bubble";
  // The host has a full-page console to open from the drawer.
  const expandable = closable && params.get("expand") === "1";

  useEffect(
    () =>
      onHostMessage((m) => {
        if (m.type !== "auth.token") return;
        tokenRef.current = m.token;
        setToken(m.token);
      }),
    [],
  );

  // Asks the dashboard for a token; if none comes, say so instead of waiting forever.
  const [timedOut, setTimedOut] = useState(false);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    if (token) return;
    if (attempt === 0) postToHost({ type: "console.ready" });
    postToHost({ type: "auth.request", reason: "initial" });
    const timer = setTimeout(() => setTimedOut(true), SIGN_IN_TIMEOUT_MS);
    return () => clearTimeout(timer);
  }, [token, attempt]);

  // Ask for a fresh token shortly before this one expires.
  useEffect(() => {
    if (!token) return;
    const left = expiresIn(token);
    if (left === null) return;
    const timer = setTimeout(
      () => postToHost({ type: "auth.request", reason: "expired" }),
      Math.max(left - REFRESH_BEFORE_MS, 5_000),
    );
    return () => clearTimeout(timer);
  }, [token]);

  const unauthorized = useCallback(() => postToHost({ type: "auth.request", reason: "unauthorized" }), []);

  if (!token) {
    return (
      <div className="console-waiting" role="status">
        {timedOut ? (
          <div className="panel-state">
            <p>
              <strong>Couldn't sign you in</strong>
            </p>
            <p>Your dashboard didn't send a sign-in for the support console. Signing in again usually fixes it.</p>
            <button
              type="button"
              className="button button-quiet"
              onClick={() => {
                setTimedOut(false);
                setAttempt((n) => n + 1);
              }}
            >
              Try again
            </button>
          </div>
        ) : (
          <p className="muted">Signing you in…</p>
        )}
      </div>
    );
  }
  return (
    <ConsoleApp
      getToken={getToken}
      onUnauthorized={unauthorized}
      onClose={closable ? () => postToHost({ type: "console.close" }) : undefined}
      onExpand={expandable ? (conversationId) => postToHost({ type: "console.expand", conversationId }) : undefined}
      initialConversation={params.get("conversation")}
    />
  );
}

function storedToken() {
  try {
    return sessionStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

function storeToken(token: string | null) {
  try {
    if (token) sessionStorage.setItem(TOKEN_KEY, token);
    else sessionStorage.removeItem(TOKEN_KEY);
  } catch {
    // The console still works for this page if storage is blocked.
  }
}

function StandaloneConsole() {
  const [token, setToken] = useState<string | null>(storedToken);
  const getToken = useCallback(() => token ?? "", [token]);

  const signOut = useCallback(() => {
    storeToken(null);
    setToken(null);
  }, []);

  if (!token) {
    return (
      <SignIn
        onToken={(t) => {
          storeToken(t);
          setToken(t);
        }}
      />
    );
  }
  return <ConsoleApp key={token} getToken={getToken} onUnauthorized={signOut} onSignOut={signOut} />;
}

function SignIn({ onToken }: { onToken: (token: string) => void }) {
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function devSignIn() {
    setBusy(true);
    setError(null);
    try {
      onToken((await devApi.agentToken("dev-agent", "Sam")).accessToken);
    } catch {
      setError("Dev sign-in isn't available on this server.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="console-signin">
      <h1>Agent console</h1>
      <p className="muted">Open this console from your dashboard to sign in.</p>
      <button type="button" className="button" onClick={devSignIn} disabled={busy}>
        Sign in as dev agent
      </button>
      {error && <p className="error-text">{error}</p>}
    </main>
  );
}
