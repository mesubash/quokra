import { useCallback, useState } from "react";
import { devApi } from "../lib/api";
import { ConsoleApp } from "./ConsoleApp";

const TOKEN_KEY = "quokra.agentToken";

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

// The agent console. In a host dashboard the token comes from the host (see sdk.js);
// on its own, local development can sign in as a dev agent.
export function Console() {
  const [token, setToken] = useState<string | null>(storedToken);

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
  return <ConsoleApp key={token} token={token} onSignedOut={signOut} />;
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
      <p className="muted">Waiting for sign-in from your dashboard…</p>
      <button type="button" className="button" onClick={devSignIn} disabled={busy}>
        Sign in as dev agent
      </button>
      {error && <p className="error-text">{error}</p>}
    </main>
  );
}
