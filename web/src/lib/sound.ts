// Short notification sounds made with Web Audio, so there's no sound file to load. Each kind of
// notification has its own little tune, so agents can tell them apart without looking.
// Browsers only allow sound after the page has been clicked once; until then this stays silent.
export type Tune = "waiting" | "message" | "transfer" | "note";

const TUNES: Record<Tune, number[]> = {
  waiting: [660, 990], // rising: someone new is waiting
  message: [880], // one soft note: a reply in my chat
  transfer: [523, 659, 784], // three notes: a chat is now mine
  note: [988, 784], // falling: a colleague left a note
};

// How loud, as a peak level. Agents pick one in the bell menu; customers get MEDIUM.
export type Volume = "low" | "medium" | "high";
export const VOLUME: Record<Volume, number> = { low: 0.18, medium: 0.35, high: 0.6 };

let context: AudioContext | null = null;

// Each note is a triangle wave with a soft octave above it: fuller than a plain sine, so it's heard over
// other sounds without being harsh.
export function chime(tune: Tune = "waiting", level = VOLUME.high) {
  try {
    context ??= new AudioContext();
    if (context.state === "suspended") void context.resume();
    const start = context.currentTime;
    TUNES[tune].forEach((frequency, i) => {
      const at = start + i * 0.14;
      for (const [multiple, share] of [
        [1, 1],
        [2, 0.25],
      ]) {
        const osc = context!.createOscillator();
        const gain = context!.createGain();
        osc.type = "triangle";
        osc.frequency.value = frequency * multiple;
        gain.gain.setValueAtTime(0.0001, at);
        gain.gain.exponentialRampToValueAtTime(Math.max(0.0002, level * share), at + 0.02);
        gain.gain.exponentialRampToValueAtTime(0.0001, at + 0.45);
        osc.connect(gain).connect(context!.destination);
        osc.start(at);
        osc.stop(at + 0.5);
      }
    });
  } catch {
    // No audio available (old browser, tests): nothing to do.
  }
}
