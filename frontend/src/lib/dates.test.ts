import { describe, expect, it } from "vitest";
import { monthRange, todayKst } from "./dates";

describe("dates", () => {
  it("UTC 자정 직전이라도 한국 날짜 기준으로 계산한다", () => {
    // 2026-10-07T20:00Z = 2026-10-08 05:00 KST
    expect(todayKst(new Date("2026-10-07T20:00:00Z"))).toBe("2026-10-08");
  });

  it("월 범위 (윤년 포함)", () => {
    expect(monthRange("2026-10-08")).toEqual({ from: "2026-10-01", to: "2026-10-31" });
    expect(monthRange("2028-02-10")).toEqual({ from: "2028-02-01", to: "2028-02-29" });
  });
});
