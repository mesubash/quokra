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

let context: AudioContext | null = null;

export function chime(tune: Tune = "waiting") {
  try {
    context ??= new AudioContext();
    if (context.state === "suspended") void context.resume();
    const start = context.currentTime;
    TUNES[tune].forEach((frequency, i) => {
      const at = start + i * 0.12;
      const osc = context!.createOscillator();
      const gain = context!.createGain();
      osc.type = "sine";
      osc.frequency.value = frequency;
      gain.gain.setValueAtTime(0.0001, at);
      gain.gain.exponentialRampToValueAtTime(0.15, at + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, at + 0.35);
      osc.connect(gain).connect(context!.destination);
      osc.start(at);
      osc.stop(at + 0.4);
    });
  } catch {
    // No audio available (old browser, tests): nothing to do.
  }
}
