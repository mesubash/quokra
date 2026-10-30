import { useCallback, useEffect, useRef, useState } from "react";

// Who on the other side is typing, from "typing" events. It clears by itself if no new one comes in time
// (the sender repeats it every few seconds while typing), or at once when their message arrives.
export interface Typist {
  senderType: "CUSTOMER" | "AGENT";
  name: string | null;
}

export const TYPING_SHOWN_MS = 5000;
// How often a typing person tells the other side.
export const TYPING_EVERY_MS = 3000;

export function useTypingSignal() {
  const [who, setWho] = useState<Typist | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  const seen = useCallback((typist: Typist) => {
    setWho(typist);
    clearTimeout(timer.current);
    timer.current = setTimeout(() => setWho(null), TYPING_SHOWN_MS);
  }, []);

  const clear = useCallback(() => {
    clearTimeout(timer.current);
    setWho(null);
  }, []);

  useEffect(() => () => clearTimeout(timer.current), []);
  return { who, seen, clear };
}
