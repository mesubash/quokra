// A small robot head on the brand colour, drawn in the text colour that reads on it (white on most brands,
// dark on very light ones).
export function RobotAvatar({ size = 32 }: { size?: number }) {
  return (
    <span className="avatar avatar-robot" style={{ width: size, height: size }} aria-hidden="true">
      <svg viewBox="0 0 32 32" width={size} height={size}>
        <circle cx="16" cy="16" r="16" className="robot-bg" />
        <circle cx="16" cy="6.6" r="1.7" className="robot-face" />
        <rect x="15.3" y="7.8" width="1.4" height="2.6" className="robot-face" />
        <rect x="9" y="10.4" width="14" height="13" rx="5.4" className="robot-head" />
        <rect x="6.4" y="14.6" width="2" height="4.6" rx="1" className="robot-face" />
        <rect x="23.6" y="14.6" width="2" height="4.6" rx="1" className="robot-face" />
        <circle cx="13.4" cy="16.2" r="1.4" className="robot-face" />
        <circle cx="18.6" cy="16.2" r="1.4" className="robot-face" />
        <path d="M13.6 19.4q2.4 1.9 4.8 0" className="robot-smile" />
      </svg>
    </span>
  );
}
