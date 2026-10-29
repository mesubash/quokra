// Icons from Lucide (ISC licence, see THIRD_PARTY_NOTICES.md).

const base = {
  xmlns: "http://www.w3.org/2000/svg",
  viewBox: "0 0 24 24",
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 2,
  strokeLinecap: "round" as const,
  strokeLinejoin: "round" as const,
  "aria-hidden": true,
};

export function SendIcon({ size = 20 }: { size?: number }) {
  return (
    <svg {...base} width={size} height={size}>
      <path d="M3.714 3.048a.498.498 0 0 0-.683.627l2.843 7.627a2 2 0 0 1 0 1.396l-2.842 7.627a.498.498 0 0 0 .682.627l18-8.5a.5.5 0 0 0 0-.904z" />
      <path d="M6 12h16" />
    </svg>
  );
}

export function CloseIcon({ size = 20 }: { size?: number }) {
  return (
    <svg {...base} width={size} height={size}>
      <path d="M18 6 6 18" />
      <path d="m6 6 12 12" />
    </svg>
  );
}

export function BotIcon({ size = 20 }: { size?: number }) {
  return (
    <svg {...base} width={size} height={size}>
      <path d="M12 6V2H8" />
      <path d="M15 11v2" />
      <path d="M2 12h2" />
      <path d="M20 12h2" />
      <path d="M20 16a2 2 0 0 1-2 2H8.828a2 2 0 0 0-1.414.586l-2.202 2.202A.71.71 0 0 1 4 20.286V8a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2z" />
      <path d="M9 11v2" />
    </svg>
  );
}

export function BackIcon({ size = 20 }: { size?: number }) {
  return (
    <svg {...base} width={size} height={size}>
      <path d="m15 18-6-6 6-6" />
    </svg>
  );
}

export function MoreIcon({ size = 20 }: { size?: number }) {
  return (
    <svg {...base} width={size} height={size}>
      <circle cx="12" cy="12" r="1" />
      <circle cx="19" cy="12" r="1" />
      <circle cx="5" cy="12" r="1" />
    </svg>
  );
}

export function PanelIcon({ size = 20 }: { size?: number }) {
  return (
    <svg {...base} width={size} height={size}>
      <rect width="18" height="18" x="3" y="3" rx="2" />
      <path d="M15 3v18" />
    </svg>
  );
}
