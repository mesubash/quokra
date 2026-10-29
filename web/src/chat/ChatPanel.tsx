import { useEffect, useState } from "react";
import { widgetApi, type Tenant } from "../lib/api";
import { getSession } from "./session";

type State =
  | { status: "loading" }
  | { status: "ready"; tenant: Tenant; visitorId: string }
  | { status: "error"; message: string };

export function ChatPanel({ slug }: { slug: string }) {
  const pk = new URLSearchParams(window.location.search).get("pk");
  const [state, setState] = useState<State>({ status: "loading" });

  useEffect(() => {
    if (!pk) {
      setState({ status: "error", message: "Missing publishable key." });
      return;
    }
    let cancelled = false;
    Promise.all([widgetApi.tenant(pk), getSession(pk)])
      .then(([tenant, session]) => {
        if (cancelled) return;
        if (tenant.slug !== slug) {
          setState({ status: "error", message: "This chat isn't set up for this page." });
          return;
        }
        if (tenant.branding.primaryColor) {
          document.documentElement.style.setProperty("--primary", tenant.branding.primaryColor);
        }
        setState({ status: "ready", tenant, visitorId: session.visitorId });
      })
      .catch(() => !cancelled && setState({ status: "error", message: "Chat is unavailable right now." }));
    return () => {
      cancelled = true;
    };
  }, [pk, slug]);

  if (state.status === "loading") return <p className="panel muted">Loading…</p>;
  if (state.status === "error") return <p className="panel muted">{state.message}</p>;

  return (
    <main className="panel">
      <header className="panel-header">
        {state.tenant.branding.logoUrl && <img src={state.tenant.branding.logoUrl} alt="" height={24} />}
        <h1>{state.tenant.displayName}</h1>
      </header>
      <p className="muted">Connected as guest.</p>
    </main>
  );
}
