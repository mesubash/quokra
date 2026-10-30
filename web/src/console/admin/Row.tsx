import type { ReactNode } from "react";

// One setting: what it is on the left, the control on the right. Narrow panes stack them.
export function Row({ label, help, children }: { label: ReactNode; help?: ReactNode; children: ReactNode }) {
  return (
    <div className="setting">
      <div className="setting-label">
        <span>{label}</span>
        {help && <p>{help}</p>}
      </div>
      <div className="setting-control">{children}</div>
    </div>
  );
}
