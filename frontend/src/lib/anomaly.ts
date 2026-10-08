// 이상거래 화면용 순수 함수. 점수·판정은 서버가 하고, 여기서는 표시와 입력 검증만 한다.
import type { RiskLevel } from "./types";
import { formatMoney } from "./money";

/** 서버 RiskLevel.of와 같은 구간 (0~39 LOW, 40~69 MEDIUM, 70~84 HIGH, 85~100 CRITICAL) */
export function riskLevelOf(score: number): RiskLevel {
  if (score >= 85) return "CRITICAL";
  if (score >= 70) return "HIGH";
  return score >= 40 ? "MEDIUM" : "LOW";
}

export const RISK_LEVELS: RiskLevel[] = ["CRITICAL", "HIGH", "MEDIUM", "LOW"];

/** spec의 특성 이름 → 화면 표시 이름 */
export const FEATURE_LABEL: Record<string, string> = {
  amount: "금액",
  transactionHour: "거래 시각",
  weekday: "요일",
  merchantFrequency: "거래처 거래 수(90일)",
  averageAmount: "거래처 평균 금액(180일)",
  amountRatioToAverage: "평균 대비 배수",
  monthlyTransactionCount: "같은 달 거래처 거래 수",
  accountCategory: "계정과목",
  daysSinceLastTransaction: "직전 거래 후 경과일",
  priorCount: "평균 계산에 쓴 거래 수",
};

const MONEY_FEATURES = new Set(["amount", "averageAmount"]);
const WEEKDAYS = ["", "월", "화", "수", "목", "금", "토", "일"];

/**
 * 특성 값을 사람이 읽기 좋게. 금액 특성은 서버가 문자열로 주면 그대로 형식화하고,
 * ML 근거처럼 숫자로 오면 원 단위로 반올림해 보여준다 (표시 전용, 계산에 쓰지 않음).
 */
export function formatFeature(name: string, value: unknown): string {
  if (value === null || value === undefined || value === "") return "-";
  if (MONEY_FEATURES.has(name)) {
    const text = typeof value === "number" ? Math.round(value).toString() : String(value);
    return `${formatMoney(text)}원`;
  }
  if (name === "weekday" && typeof value === "number") return `${WEEKDAYS[Math.round(value)] ?? value}요일`;
  if (name === "transactionHour" && typeof value === "number") return value < 0 ? "-" : `${Math.round(value)}시`;
  if (name === "amountRatioToAverage" && typeof value === "number") return `${value.toFixed(1)}배`;
  if (name === "daysSinceLastTransaction" && typeof value === "number") return `${Math.round(value)}일`;
  if (typeof value === "number") return Number.isInteger(value) ? value.toLocaleString("ko-KR") : value.toFixed(2);
  return String(value);
}

function addDays(date: string, days: number): string {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** 기본 분석 기간: 2개월 전 1일 ~ 오늘 (이번 달 포함 3개월) */
export function defaultScanPeriod(today: string): { from: string; to: string } {
  const [y, m] = today.split("-").map(Number);
  const start = new Date(Date.UTC(y, m - 3, 1));
  return { from: start.toISOString().slice(0, 10), to: today };
}

export const MAX_SCAN_DAYS = 366;

/** 서버 검증과 같은 규칙으로 즉시 피드백. 문제가 없으면 null */
export function scanPeriodError(from: string, to: string): string | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(from) || !/^\d{4}-\d{2}-\d{2}$/.test(to)) return "기간을 입력하세요.";
  if (from > to) return "시작일이 종료일보다 늦습니다.";
  if (addDays(from, MAX_SCAN_DAYS - 1) < to) return `분석 기간은 최대 ${MAX_SCAN_DAYS}일입니다.`;
  return null;
}

/** 규칙 코드 → 목록에 쓰는 짧은 이름 (상세 화면은 서버가 준 제목·사유를 그대로 쓴다) */
export const RULE_SHORT_LABEL: Record<string, string> = {
  DUPLICATE_PAYMENT: "중복 결제",
  SPLIT_PAYMENT: "쪼개기 결제",
  NEAR_LIMIT: "기준 직전 금액",
  AMOUNT_SPIKE: "평균 대비 고액",
  NEW_VENDOR_HIGH: "신규 거래처 고액",
  DUPLICATE_RECEIPT: "영수증 중복",
  MISSING_EVIDENCE: "증빙 없음",
  VENDOR_REPEAT: "반복 결제",
  OFF_HOURS: "주말·심야",
  ACCOUNT_SURGE: "계정과목 급증",
};
