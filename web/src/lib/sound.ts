// A short two-note chime for a new waiting chat, made with Web Audio so there's no sound file to load.
// Browsers only allow sound after the page has been clicked once; until then this stays silent.
let context: AudioContext | null = null;

export function chime() {
  try {
    context ??= new AudioContext();
    if (context.state === "suspended") void context.resume();
    const start = context.currentTime;
    [660, 990].forEach((frequency, i) => {
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

const KEY = "quokra.sound";

export function soundEnabled() {
  try {
    return localStorage.getItem(KEY) !== "off";
  } catch {
    return true;
  }
}

export function setSoundEnabled(on: boolean) {
  try {
    localStorage.setItem(KEY, on ? "on" : "off");
  } catch {
    // Storage blocked: the choice lasts until the page reloads.
  }
}
