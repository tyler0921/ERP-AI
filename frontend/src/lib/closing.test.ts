import { describe, expect, it } from "vitest";
import { currentMonthKst, defaultClosingMonth, formatManWon, isSelectableMonth, monthLabel, niceTicks, shiftMonth } from "./closing";

describe("closing", () => {
  it("월 이동 (연도 경계 포함)", () => {
    expect(shiftMonth("2026-01", -1)).toBe("2025-12");
    expect(shiftMonth("2025-12", 1)).toBe("2026-01");
    expect(shiftMonth("2026-09", -12)).toBe("2025-09");
    expect(shiftMonth("2026-09", 0)).toBe("2026-09");
  });

  it("이번 달·기본 마감 월은 한국 시간 기준", () => {
    // 2026-09-30T16:00Z = 2026-10-01 01:00 KST
    const now = new Date("2026-09-30T16:00:00Z");
    expect(currentMonthKst(now)).toBe("2026-10");
    expect(defaultClosingMonth(now)).toBe("2026-09");
  });

  it("선택 가능한 월", () => {
    expect(isSelectableMonth("2026-09", "2026-10")).toBe(true);
    expect(isSelectableMonth("2026-10", "2026-10")).toBe(true);
    expect(isSelectableMonth("2026-11", "2026-10")).toBe(false);
    expect(isSelectableMonth("2026-13", "2026-10")).toBe(false);
    expect(isSelectableMonth("2026-9", "2026-10")).toBe(false);
    expect(isSelectableMonth("1999-12", "2026-10")).toBe(false);
    expect(isSelectableMonth(null, "2026-10")).toBe(false);
  });

  it("월 표기", () => {
    expect(monthLabel("2026-09")).toBe("2026년 9월");
  });

  it("축 눈금은 1·2·5 간격으로 최댓값을 덮는다", () => {
    expect(niceTicks(20_000_000)).toEqual([0, 5_000_000, 10_000_000, 15_000_000, 20_000_000]);
    expect(niceTicks(21_000_000)).toEqual([0, 10_000_000, 20_000_000, 30_000_000]);
    expect(niceTicks(0)).toEqual([0]);
  });

  it("만원 표기", () => {
    expect(formatManWon("12000000.00")).toBe("1,200만");
    expect(formatManWon("5000")).toBe("0.5만");
    expect(formatManWon("-3000000")).toBe("-300만");
    expect(formatManWon(0)).toBe("0");
    expect(formatManWon(15_000_000)).toBe("1,500만");
    expect(formatManWon(-3_000_000)).toBe("-300만");
  });
});
