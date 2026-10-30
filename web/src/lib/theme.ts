import { textOn } from "./color";

// Light, dark, or "auto" to follow the visitor's system setting. The host picks it when embedding
// (?theme=) and can change it later (theme.set), e.g. when its own dark mode is toggled.
export type Theme = "light" | "dark" | "auto";

let current: Theme = "light";
const media = window.matchMedia?.("(prefers-color-scheme: dark)");

function apply() {
  const dark = current === "dark" || (current === "auto" && !!media?.matches);
  document.documentElement.dataset.theme = dark ? "dark" : "light";
}

export function setTheme(theme: unknown) {
  if (theme !== "light" && theme !== "dark" && theme !== "auto") return;
  current = theme;
  apply();
}

media?.addEventListener?.("change", apply);

// The host's own brand colour (?accent=#rrggbb), used instead of the tenant's when given.
export const accent = (() => {
  const value = new URLSearchParams(window.location.search).get("accent");
  return value && /^#[0-9a-fA-F]{6}$/.test(value) ? value : null;
})();

export function applyColor(color: string) {
  document.documentElement.style.setProperty("--primary", color);
  document.documentElement.style.setProperty("--on-primary", textOn(color));
}
