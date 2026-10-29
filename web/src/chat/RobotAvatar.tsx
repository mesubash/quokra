// A small, friendly robot head in the brand colour. The launcher keeps the full robot.
export function RobotAvatar({ size = 32 }: { size?: number }) {
  return (
    <span className="avatar avatar-robot" style={{ width: size, height: size }} aria-hidden="true">
      <svg viewBox="0 0 32 32" width={size} height={size}>
        <circle cx="16" cy="16" r="16" className="robot-bg" />
        <path d="M16 6.5v3" className="robot-line" />
        <circle cx="16" cy="6" r="1.6" className="robot-face" />
        <rect x="6.5" y="13.5" width="2" height="5" rx="1" className="robot-face" />
        <rect x="23.5" y="13.5" width="2" height="5" rx="1" className="robot-face" />
        <rect x="8.5" y="9.5" width="15" height="13" rx="5" className="robot-face" />
        <circle cx="13" cy="15" r="1.7" className="robot-eye" />
        <circle cx="19" cy="15" r="1.7" className="robot-eye" />
        <path d="M13 18.6q3 2.2 6 0" className="robot-smile" />
      </svg>
    </span>
  );
}
