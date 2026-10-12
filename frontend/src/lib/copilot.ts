// AI Copilot 화면 유틸: 계획(해석) 표시 이름과 편집 폼 정리. 최종 검증은 백엔드 PlanValidator가 한다.

import type { CopilotPlan, CopilotStatus, CopilotTool, PeriodType, PlanSource, RiskLevel } from "./types";

export const TOOL_LABEL: Record<CopilotTool, string> = {
  PROFIT_SUMMARY: "손익 요약",
  ACCOUNT_SUMMARY: "계정과목 금액",
  VENDOR_RANKING: "거래처별 비용 순위",
  ANOMALY_LIST: "이상거래 목록",
  CLOSING_STATUS: "월마감 현황",
  UNSUPPORTED: "지원하지 않음",
};

export const PERIOD_LABEL: Record<PeriodType, string> = {
  THIS_MONTH: "이번 달",
  LAST_MONTH: "지난 달",
  MONTH: "특정 월",
  THIS_QUARTER: "이번 분기",
  LAST_QUARTER: "지난 분기",
  QUARTER: "특정 분기",
  THIS_YEAR: "올해",
  LAST_YEAR: "작년",
  YEAR: "특정 연도",
};

export const COMPARE_LABEL = { PREV_PERIOD: "직전 기간 대비", PREV_YEAR: "전년 같은 기간 대비" } as const;
export const ORDER_LABEL = { AMOUNT: "금액 순", INCREASE: "증가액 순", DECREASE: "감소액 순" } as const;
export const MIN_LEVEL_LABEL: Record<RiskLevel, string> = { LOW: "전체", MEDIUM: "중간 이상", HIGH: "높음 이상", CRITICAL: "심각만" };
export const ANOMALY_STATUS_FILTER_LABEL = { OPEN: "검토 대기만", ALL: "판정 포함 전체" } as const;

export const PLAN_SOURCE_LABEL: Record<PlanSource, string> = {
  LLM: "AI 해석",
  RULE: "규칙 해석(AI 대체)",
  USER: "직접 수정",
};

export const COPILOT_STATUS_LABEL: Record<CopilotStatus, string> = {
  PLANNING: "해석 중",
  ANSWERED: "조회 완료",
  UNSUPPORTED: "지원하지 않음",
  FAILED: "실패",
};

export const EXAMPLE_QUESTIONS = [
  "지난달 광고비가 얼마나 늘었어?",
  "지난달 비용이 가장 많이 늘어난 거래처는?",
  "이번 달 고위험 이상거래 보여줘",
  "3분기 매출과 영업이익은?",
  "지난달 마감 진행 상황은?",
];

/** 도구가 쓰는 파라미터 */
export function toolFields(tool: CopilotTool) {
  return {
    period: tool !== "UNSUPPORTED",
    compare: tool === "PROFIT_SUMMARY" || tool === "ACCOUNT_SUMMARY" || tool === "VENDOR_RANKING",
    account: tool === "ACCOUNT_SUMMARY" ? ("required" as const) : tool === "VENDOR_RANKING" ? ("optional" as const) : false,
    order: tool === "VENDOR_RANKING",
    anomaly: tool === "ANOMALY_LIST",
    limit: tool === "VENDOR_RANKING" || tool === "ANOMALY_LIST",
    reason: tool === "UNSUPPORTED",
  };
}

/** 편집 폼 상태 → 보낼 계획. 도구·기간 유형에 맞지 않는 값은 뺀다 */
export function cleanPlan(plan: CopilotPlan): CopilotPlan {
  const f = toolFields(plan.tool);
  const out: CopilotPlan = { tool: plan.tool };
  if (f.reason) {
    if (plan.reason?.trim()) out.reason = plan.reason.trim();
    return out;
  }
  if (plan.period) {
    const { type, year, month, quarter } = plan.period;
    const p: NonNullable<CopilotPlan["period"]> = { type };
    if ((type === "MONTH" || type === "QUARTER" || type === "YEAR") && year) p.year = year;
    if (type === "MONTH" && month) p.month = month;
    if (type === "QUARTER" && quarter) p.quarter = quarter;
    out.period = p;
  }
  if (f.compare && plan.compare) out.compare = plan.compare;
  if (f.account && plan.account?.trim()) out.account = plan.account.trim();
  if (f.order && plan.order) out.order = plan.order;
  if (f.anomaly) {
    if (plan.minLevel) out.minLevel = plan.minLevel;
    if (plan.status) out.status = plan.status;
  }
  if (f.limit && plan.limit) out.limit = plan.limit;
  return out;
}

/** 편집 폼에서 바로 알 수 있는 문제 (서버도 다시 검증한다) */
export function planProblems(plan: CopilotPlan): string[] {
  const problems: string[] = [];
  const f = toolFields(plan.tool);
  const p = plan.period;
  if (p?.type === "MONTH" && !(p.month && p.month >= 1 && p.month <= 12)) problems.push("월(1~12)을 입력하세요.");
  if (p?.type === "QUARTER" && !(p.quarter && p.quarter >= 1 && p.quarter <= 4)) problems.push("분기(1~4)를 입력하세요.");
  if (p?.type === "YEAR" && !p.year) problems.push("연도를 입력하세요.");
  if (p?.year !== undefined && p.year !== null && (p.year < 2000 || p.year > 2100)) problems.push("연도는 2000~2100이어야 합니다.");
  if (f.account === "required" && !plan.account?.trim()) problems.push("계정과목을 고르세요.");
  if (f.limit && plan.limit !== undefined && (plan.limit < 1 || plan.limit > 20 || !Number.isInteger(plan.limit)))
    problems.push("건수는 1~20 사이의 정수여야 합니다.");
  return problems;
}

/** 해석을 사람이 읽는 조각으로: ["계정과목 금액", "광고선전비", "직전 기간 대비"] (기간은 결과의 실제 기간을 따로 표시) */
export function describePlan(plan: CopilotPlan): string[] {
  const parts: string[] = [TOOL_LABEL[plan.tool]];
  const f = toolFields(plan.tool);
  if (f.account && plan.account) parts.push(plan.account);
  if (f.order) parts.push(ORDER_LABEL[plan.order ?? "AMOUNT"]);
  if (f.compare) parts.push(COMPARE_LABEL[plan.compare ?? "PREV_PERIOD"]);
  if (f.anomaly) {
    parts.push(MIN_LEVEL_LABEL[plan.minLevel ?? "LOW"]);
    parts.push(ANOMALY_STATUS_FILTER_LABEL[plan.status ?? "OPEN"]);
  }
  if (f.limit && plan.limit) parts.push(`${plan.limit}건`);
  return parts;
}
