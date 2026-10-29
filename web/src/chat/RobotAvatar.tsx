import { useEffect, useRef, useState } from "react";
import { BotIcon } from "../lib/icons";
import { loadRobot, type LottieAnimation } from "../lib/lottie";

// Still frame of the robot's head, from the same animation as the chat button.
// Shows a plain bot icon until (or if) the animation can't be loaded.
export function RobotAvatar({ size = 32, wave = false }: { size?: number; wave?: boolean }) {
  const holder = useRef<HTMLSpanElement>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let animation: LottieAnimation | undefined;
    let cancelled = false;
    const reduced = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
    loadRobot()
      .then(({ lottie, robot }) => {
        if (cancelled || !holder.current) return;
        animation = lottie.loadAnimation({
          container: holder.current,
          renderer: "svg",
          loop: false,
          autoplay: wave && !reduced,
          animationData: robot,
          rendererSettings: { viewBoxSize: "180 108 175 175" },
        });
        if (!wave || reduced) animation.goToAndStop(0, true);
        setReady(true);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
      animation?.destroy();
    };
  }, [wave]);

  return (
    <span className="avatar" style={{ width: size, height: size }} aria-hidden="true">
      <span ref={holder} className="avatar-robot" />
      {!ready && <BotIcon size={Math.round(size * 0.6)} />}
    </span>
  );
}
