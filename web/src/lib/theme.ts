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
