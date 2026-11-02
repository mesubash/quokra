import { useEffect, useRef, useState } from "react";

// "Sign out" asks first: a slip of the mouse shouldn't end an agent's shift.
export function SignOutButton({ onConfirm }: { onConfirm: () => void }) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onClick = (e: MouseEvent) => !root.current?.contains(e.target as Node) && setOpen(false);
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    document.addEventListener("mousedown", onClick);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onClick);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  return (
    <div className="menu" ref={root}>
      <button type="button" className="link-button" aria-expanded={open} onClick={() => setOpen(!open)}>
        Sign out
      </button>
      {open && (
        <div className="menu-list sign-out-confirm" role="alertdialog" aria-label="Sign out?">
          <p>
            <strong>Sign out of the console?</strong>
            <span className="muted">You'll show as away, and new chats won't notify you here.</span>
          </p>
          <div className="sign-out-actions">
            <button type="button" className="button button-quiet button-small" onClick={() => setOpen(false)}>
              Cancel
            </button>
            <button type="button" className="button button-small" autoFocus onClick={onConfirm}>
              Sign out
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
