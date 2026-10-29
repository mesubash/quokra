// The robot's head, cropped from the same image as the chat button.
export function RobotAvatar({ size = 32 }: { size?: number }) {
  return (
    <span className="avatar avatar-robot" style={{ width: size, height: size }} aria-hidden="true">
      <img src="/robot.svg" alt="" />
    </span>
  );
}
