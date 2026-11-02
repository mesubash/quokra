// Text and icons on the brand colour: white, unless the colour is so light that white doesn't read
// (under 3:1, e.g. yellow or pale blue). Strictly "whichever contrasts more" picks black for mid tones
// like orange or red, which looks wrong on most brands.
export function textOn(background: string | null | undefined): string {
  const m = /^#([0-9a-f]{6})$/i.exec(background ?? "");
  if (!m) return "#fff";
  const n = parseInt(m[1], 16);
  const [r, g, b] = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((c) => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  });
  const luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;
  // Contrast against white is 1.05 / (L + 0.05).
  return 1.05 / (luminance + 0.05) >= 3 ? "#fff" : "#1f2328";
}
