import { describe, expect, it } from "vitest";
import { exampleDomain, suggestEmail, visitorRegion } from "./emailHelp";

describe("suggestEmail", () => {
  it("fixes common slips", () => {
    expect(suggestEmail("pat@gmial.com", null)).toBe("pat@gmail.com");
    expect(suggestEmail("pat@gmail.con", null)).toBe("pat@gmail.com");
    expect(suggestEmail("pat@gmail.co", null)).toBe("pat@gmail.com");
    expect(suggestEmail("pat@hotmial.com", null)).toBe("pat@hotmail.com");
    expect(suggestEmail("Pat.Doe@YAHO.COM", null)).toBe("Pat.Doe@yahoo.com");
  });

  it("knows providers around the world", () => {
    expect(suggestEmail("li@qq.con", "CN")).toBe("li@qq.com");
    expect(suggestEmail("li@163.con", "CN")).toBe("li@163.com");
    expect(suggestEmail("kim@navr.com", "KR")).toBe("kim@naver.com");
    expect(suggestEmail("ivan@yandex.ri", "RU")).toBe("ivan@yandex.ru");
  });

  it("leaves good and unknown addresses alone", () => {
    expect(suggestEmail("pat@gmail.com", null)).toBeNull();
    expect(suggestEmail("li@qq.com", "CN")).toBeNull();
    expect(suggestEmail("ops@himalayanguardian.com", null)).toBeNull();
    expect(suggestEmail("ram@wlink.com.np", null)).toBeNull();
    expect(suggestEmail("not an email", null)).toBeNull();
  });
});

describe("visitorRegion", () => {
  it("reads the time zone first, then the language", () => {
    expect(visitorRegion("Asia/Shanghai", ["en-US"])).toBe("CN");
    expect(visitorRegion("UTC", ["zh-CN"])).toBe("CN");
    expect(visitorRegion("UTC", ["ko-KR"])).toBe("KR");
    expect(visitorRegion("Asia/Kathmandu", ["en-US"])).toBeNull();
  });

  it("gives an example address from the region", () => {
    expect(exampleDomain("CN")).toBe("qq.com");
    expect(exampleDomain(null)).toBe("gmail.com");
  });
});
