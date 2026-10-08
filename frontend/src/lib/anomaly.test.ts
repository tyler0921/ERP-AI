import { describe, expect, it } from "vitest";
import { defaultScanPeriod, formatFeature, riskLevelOf, scanPeriodError } from "./anomaly";

describe("anomaly", () => {
  it("Risk Level 구간은 서버와 같다", () => {
    expect(riskLevelOf(0)).toBe("LOW");
    expect(riskLevelOf(39)).toBe("LOW");
    expect(riskLevelOf(40)).toBe("MEDIUM");
    expect(riskLevelOf(69)).toBe("MEDIUM");
    expect(riskLevelOf(70)).toBe("HIGH");
    expect(riskLevelOf(84)).toBe("HIGH");
    expect(riskLevelOf(85)).toBe("CRITICAL");
    expect(riskLevelOf(100)).toBe("CRITICAL");
  });

  it("기본 분석 기간은 2개월 전 1일부터 오늘까지 (연도 경계 포함)", () => {
    expect(defaultScanPeriod("2026-10-08")).toEqual({ from: "2026-08-01", to: "2026-10-08" });
    expect(defaultScanPeriod("2027-01-15")).toEqual({ from: "2026-11-01", to: "2027-01-15" });
  });

  it("분석 기간 검증 (최대 366일)", () => {
    expect(scanPeriodError("2026-09-01", "2026-09-30")).toBeNull();
    expect(scanPeriodError("2026-09-30", "2026-09-01")).toContain("늦습니다");
    expect(scanPeriodError("2025-10-01", "2026-09-30")).toBeNull(); // 365일
    expect(scanPeriodError("2024-01-01", "2024-12-31")).toBeNull(); // 윤년 366일
    expect(scanPeriodError("2024-01-01", "2025-01-01")).toContain("366일");
    expect(scanPeriodError("", "2026-09-30")).not.toBeNull();
  });

  it("특성 값 표시", () => {
    expect(formatFeature("amount", "4950000.00")).toBe("4,950,000원");
    expect(formatFeature("averageAmount", 650000.4)).toBe("650,000원");
    expect(formatFeature("weekday", 6)).toBe("토요일");
    expect(formatFeature("transactionHour", 23)).toBe("23시");
    expect(formatFeature("transactionHour", -1)).toBe("-");
    expect(formatFeature("amountRatioToAverage", 7.615)).toBe("7.6배");
    expect(formatFeature("daysSinceLastTransaction", 0)).toBe("0일");
    expect(formatFeature("merchantFrequency", 1234)).toBe("1,234");
    expect(formatFeature("accountCategory", "5400")).toBe("5400");
    expect(formatFeature("averageAmount", null)).toBe("-");
  });
});
