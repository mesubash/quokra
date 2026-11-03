// Catches typos in the email a guest gives before talking to a person ("pat@gmial.com"), and suggests the fix.
// Only a suggestion: unknown domains (a company's own, say) are never questioned. The visitor's region, guessed
// from the browser's time zone and language (nothing is sent anywhere), decides which providers come first:
// Gmail is blocked in China, so "163.con" there should become 163.com, not gmail.com.

// Providers by region. Adding one is a line here.
const REGIONS: Record<string, { timeZones: string[]; languages: string[]; domains: string[] }> = {
  CN: {
    timeZones: ["Asia/Shanghai", "Asia/Chongqing", "Asia/Harbin", "Asia/Urumqi"],
    languages: ["zh-CN", "zh-Hans"],
    domains: ["qq.com", "163.com", "126.com", "foxmail.com", "yeah.net", "sina.com", "sina.cn", "sohu.com", "aliyun.com", "139.com"],
  },
  IN: {
    timeZones: ["Asia/Kolkata", "Asia/Calcutta"],
    languages: ["hi", "en-IN"],
    domains: ["rediffmail.com", "yahoo.co.in"],
  },
  JP: {
    timeZones: ["Asia/Tokyo"],
    languages: ["ja"],
    domains: ["yahoo.co.jp", "docomo.ne.jp", "ezweb.ne.jp", "i.softbank.jp"],
  },
  KR: {
    timeZones: ["Asia/Seoul"],
    languages: ["ko"],
    domains: ["naver.com", "daum.net", "hanmail.net", "kakao.com"],
  },
  RU: {
    timeZones: ["Europe/Moscow", "Asia/Yekaterinburg", "Asia/Novosibirsk", "Europe/Samara"],
    languages: ["ru"],
    domains: ["yandex.ru", "yandex.com", "mail.ru", "rambler.ru"],
  },
  DE: {
    timeZones: ["Europe/Berlin", "Europe/Vienna", "Europe/Zurich"],
    languages: ["de"],
    domains: ["gmx.de", "gmx.net", "web.de", "t-online.de"],
  },
  FR: { timeZones: ["Europe/Paris"], languages: ["fr"], domains: ["orange.fr", "free.fr", "laposte.net", "wanadoo.fr"] },
  IT: { timeZones: ["Europe/Rome"], languages: ["it"], domains: ["libero.it", "virgilio.it"] },
  GB: { timeZones: ["Europe/London"], languages: ["en-GB"], domains: ["btinternet.com", "yahoo.co.uk", "sky.com"] },
  AU: { timeZones: ["Australia/Sydney", "Australia/Melbourne"], languages: ["en-AU"], domains: ["bigpond.com"] },
  BR: { timeZones: ["America/Sao_Paulo"], languages: ["pt-BR"], domains: ["uol.com.br", "bol.com.br"] },
};

const GLOBAL = [
  "gmail.com", "outlook.com", "hotmail.com", "live.com", "yahoo.com", "icloud.com", "me.com",
  "proton.me", "protonmail.com", "aol.com", "msn.com", "zoho.com",
];

// Endings that are almost always a slip for .com.
const BAD_ENDINGS = ["con", "cmo", "comm", "coom", "cpm", "xom", "vom", "om", "cm", "ocm", "conm"];

// The visitor's region from the browser, or null when it says nothing we know.
export function visitorRegion(
  timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone,
  languages: readonly string[] = typeof navigator === "undefined" ? [] : navigator.languages ?? [navigator.language],
): string | null {
  for (const [region, r] of Object.entries(REGIONS)) {
    if (r.timeZones.includes(timeZone)) return region;
  }
  for (const language of languages) {
    for (const [region, r] of Object.entries(REGIONS)) {
      if (r.languages.some((l) => language === l || language.startsWith(l + "-") || (!l.includes("-") && language.split("-")[0] === l))) {
        return region;
      }
    }
  }
  return null;
}

// Domains to compare against: the region's own first, then the global ones, then everyone else's.
function domainsFor(region: string | null) {
  const own = region ? REGIONS[region]?.domains ?? [] : [];
  const others = Object.entries(REGIONS).filter(([r]) => r !== region).flatMap(([, r]) => r.domains);
  return [...own, ...GLOBAL, ...others];
}

// "e.g. name@qq.com" for the placeholder.
export function exampleDomain(region = visitorRegion()) {
  return (region && REGIONS[region]?.domains[0]) || "gmail.com";
}

// The corrected email, or null when it looks fine (or isn't close to anything we know).
export function suggestEmail(email: string, region = visitorRegion()): string | null {
  const at = email.lastIndexOf("@");
  if (at < 1) return null;
  const local = email.slice(0, at);
  const domain = email.slice(at + 1).trim().toLowerCase();
  if (!domain) return null;
  const known = domainsFor(region);
  if (known.includes(domain)) return null;

  // gmail.con → gmail.com
  const dot = domain.lastIndexOf(".");
  if (dot > 0 && BAD_ENDINGS.includes(domain.slice(dot + 1))) {
    const fixed = domain.slice(0, dot) + ".com";
    if (known.includes(fixed)) return `${local}@${fixed}`;
  }

  // gmial.com → gmail.com: the closest known domain, a slip or two away. Earlier in the list wins ties.
  const limit = domain.length < 8 ? 1 : 2;
  let best: string | null = null;
  let bestDistance = limit + 1;
  for (const candidate of known) {
    const d = distance(domain, candidate);
    if (d < bestDistance) {
      best = candidate;
      bestDistance = d;
    }
  }
  return best ? `${local}@${best}` : null;
}

// Edits between two words, counting a swap of neighbours (gmial / gmail) as one.
function distance(a: string, b: string) {
  if (Math.abs(a.length - b.length) > 2) return 99;
  const d = Array.from({ length: a.length + 1 }, (_, i) => [i, ...Array(b.length).fill(0)]);
  for (let j = 1; j <= b.length; j++) d[0][j] = j;
  for (let i = 1; i <= a.length; i++) {
    for (let j = 1; j <= b.length; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost);
      if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
    }
  }
  return d[a.length][b.length];
}
