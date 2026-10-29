import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { ChatPanel } from "./chat/ChatPanel";
import { Console } from "./console/Console";
import "./styles.css";

function App() {
  const path = window.location.pathname;
  const chat = path.match(/^\/w\/([a-z0-9-]+)\/?$/);

  if (chat) return <ChatPanel slug={chat[1]} />;
  if (path === "/console") return <Console />;
  return <p className="not-found">Not found</p>;
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
