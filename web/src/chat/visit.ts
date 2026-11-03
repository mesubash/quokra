import type { VisitInfo } from "../lib/api";

// What the chat sends about this visit when a conversation starts: the page and campaign from sdk.js (in this
// frame's address), and the browser's language, time zone and kind of device. No IP address; the server adds
// the country from Cloudflare.
export function currentVisit(search = window.location.search, userAgent = navigator.userAgent): VisitInfo {
  const params = new URLSearchParams(search);
  return {
    page: params.get("page") ?? undefined,
    referrer: params.get("ref") ?? undefined,
    utmSource: params.get("utm_source") ?? undefined,
    utmMedium: params.get("utm_medium") ?? undefined,
    utmCampaign: params.get("utm_campaign") ?? undefined,
    device: deviceOf(userAgent),
    language: navigator.language,
    timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    siteVisitor: params.get("sv") ?? undefined,
  };
}

// From the browser's own description; the frame's width says nothing (it's always the chat's width).
export function deviceOf(userAgent: string): "mobile" | "tablet" | "desktop" {
  if (/iPad|Tablet|Android(?!.*Mobile)/i.test(userAgent)) return "tablet";
  if (/Mobi|iPhone|Android/i.test(userAgent)) return "mobile";
  return "desktop";
}
