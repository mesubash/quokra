import { expect, it } from "vitest";
import { currentVisit, deviceOf } from "./visit";

it("reads the page and campaign sdk.js passed, and the kind of device", () => {
  const visit = currentVisit("?pk=x&page=%2Fplans&ref=www.google.com&utm_campaign=ebc-oct", "Mozilla/5.0 (iPhone)");
  expect(visit).toMatchObject({ page: "/plans", referrer: "www.google.com", utmCampaign: "ebc-oct", device: "mobile" });
  expect(deviceOf("Mozilla/5.0 (iPad; CPU OS 17_0)")).toBe("tablet");
  expect(deviceOf("Mozilla/5.0 (Linux; Android 14; SM-X710)")).toBe("tablet");
  expect(deviceOf("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0)")).toBe("desktop");
});
