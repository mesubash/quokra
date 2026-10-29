// Loads the Lottie player and the robot animation (same files sdk.js uses), once.

interface LottieAnimation {
  goToAndStop(frame: number, isFrame: boolean): void;
  goToAndPlay(frame: number, isFrame: boolean): void;
  destroy(): void;
}

interface Lottie {
  loadAnimation(options: {
    container: Element;
    renderer: "svg";
    loop: boolean;
    autoplay: boolean;
    animationData: unknown;
    rendererSettings?: { viewBoxSize?: string };
  }): LottieAnimation;
}

declare global {
  interface Window {
    lottie?: Lottie;
    QuokraAnimations?: { helloBot?: unknown };
  }
}

function loadScript(src: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const el = document.createElement("script");
    el.src = src;
    el.async = true;
    el.onload = () => resolve();
    el.onerror = () => reject(new Error(`Couldn't load ${src}`));
    document.head.appendChild(el);
  });
}

let loading: Promise<{ lottie: Lottie; robot: unknown }> | null = null;

export function loadRobot() {
  loading ??= (async () => {
    if (!window.QuokraAnimations?.helloBot) await loadScript("/animations/hello-bot.js");
    if (!window.lottie) await loadScript("/vendor/lottie_light-5.13.0.min.js");
    return { lottie: window.lottie!, robot: window.QuokraAnimations!.helloBot };
  })();
  return loading;
}

export type { LottieAnimation };
