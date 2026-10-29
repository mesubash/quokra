// Black or white text, whichever reads better on the given #rrggbb background.
export function textOn(background: string | null | undefined): string {
  const m = /^#([0-9a-f]{6})$/i.exec(background ?? "");
  if (!m) return "#fff";
  const n = parseInt(m[1], 16);
  const [r, g, b] = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((c) => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  });
  const luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;
  // Contrast against white is (1.05)/(L+0.05); against black (L+0.05)/0.05. Pick the higher.
  return 1.05 / (luminance + 0.05) >= (luminance + 0.05) / 0.05 ? "#fff" : "#1f2328";
}
