import { describe, expect, it } from "vitest";
import { cleanPlan, describePlan, planProblems } from "./copilot";

describe("copilot", () => {
  it("도구와 기간 유형에 맞지 않는 값은 보내지 않는다", () => {
    expect(
      cleanPlan({
        tool: "PROFIT_SUMMARY",
        period: { type: "THIS_MONTH", year: 2026, month: 9, quarter: 3 },
        compare: "PREV_YEAR",
        account: "광고선전비",
        order: "INCREASE",
        limit: 5,
      }),
    ).toEqual({ tool: "PROFIT_SUMMARY", period: { type: "THIS_MONTH" }, compare: "PREV_YEAR" });
    expect(cleanPlan({ tool: "ACCOUNT_SUMMARY", account: "  광고선전비 ", period: { type: "MONTH", month: 9 } })).toEqual({
      tool: "ACCOUNT_SUMMARY",
      account: "광고선전비",
      period: { type: "MONTH", month: 9 },
    });
    expect(cleanPlan({ tool: "UNSUPPORTED", reason: " 부서 없음 ", period: { type: "THIS_MONTH" } })).toEqual({
      tool: "UNSUPPORTED",
      reason: "부서 없음",
    });
    expect(cleanPlan({ tool: "VENDOR_RANKING", account: "", order: "AMOUNT", limit: 3 })).toEqual({
      tool: "VENDOR_RANKING",
      order: "AMOUNT",
      limit: 3,
    });
  });

  it("폼 검증", () => {
    expect(planProblems({ tool: "ACCOUNT_SUMMARY", period: { type: "MONTH" } })).toEqual(["월(1~12)을 입력하세요.", "계정과목을 고르세요."]);
    expect(planProblems({ tool: "ANOMALY_LIST", limit: 50 })).toEqual(["건수는 1~20 사이의 정수여야 합니다."]);
    expect(planProblems({ tool: "PROFIT_SUMMARY", period: { type: "YEAR", year: 1999 } })).toEqual(["연도는 2000~2100이어야 합니다."]);
    expect(planProblems({ tool: "VENDOR_RANKING" })).toEqual([]);
  });

  it("해석 표시 (기본값 포함)", () => {
    expect(describePlan({ tool: "VENDOR_RANKING", order: "INCREASE", limit: 3 })).toEqual(["거래처별 비용 순위", "증가액 순", "직전 기간 대비", "3건"]);
    expect(describePlan({ tool: "ANOMALY_LIST", minLevel: "HIGH" })).toEqual(["이상거래 목록", "높음 이상", "검토 대기만"]);
    expect(describePlan({ tool: "ACCOUNT_SUMMARY", account: "광고비", compare: "PREV_YEAR" })).toEqual(["계정과목 금액", "광고비", "전년 같은 기간 대비"]);
  });
});
