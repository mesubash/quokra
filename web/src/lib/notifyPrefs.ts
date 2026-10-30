// What each agent wants to be notified about, kept in this browser.
export interface NotifyPrefs {
  sound: boolean;
  browser: boolean; // system notifications while the dashboard isn't in front (if the dashboard allows them)
  waiting: boolean; // a new chat is waiting
  myMessages: boolean; // a customer writes in my chat
  transfers: boolean; // a chat is transferred to me
  notes: boolean; // a colleague leaves a note on my chat
}

const KEY = "quokra.notify";
const OLD_SOUND_KEY = "quokra.sound";

export const DEFAULT_PREFS: NotifyPrefs = {
  sound: true,
  browser: true,
  waiting: true,
  myMessages: true,
  transfers: true,
  notes: true,
};

export function loadPrefs(): NotifyPrefs {
  try {
    const saved = localStorage.getItem(KEY);
    if (saved) return { ...DEFAULT_PREFS, ...(JSON.parse(saved) as Partial<NotifyPrefs>) };
    // Before this menu there was only an on/off sound switch.
    return { ...DEFAULT_PREFS, sound: localStorage.getItem(OLD_SOUND_KEY) !== "off" };
  } catch {
    return DEFAULT_PREFS;
  }
}

export function savePrefs(prefs: NotifyPrefs) {
  try {
    localStorage.setItem(KEY, JSON.stringify(prefs));
  } catch {
    // Storage blocked: the choice lasts until the page reloads.
  }
}
