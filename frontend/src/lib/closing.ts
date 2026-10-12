// 월마감 화면 유틸. 월은 회사 기준 시간대(Asia/Seoul)의 "YYYY-MM" 문자열로 다룬다.

import { todayKst } from "./dates";
import { toCents } from "./money";

const MONTH_PATTERN = /^(\d{4})-(0[1-9]|1[0-2])$/;

export function currentMonthKst(now: Date = new Date()): string {
  return todayKst(now).slice(0, 7);
}

/** "2026-01", -1 → "2025-12" */
export function shiftMonth(month: string, delta: number): string {
  const [y, m] = month.split("-").map(Number);
  const index = y * 12 + (m - 1) + delta;
  return `${Math.floor(index / 12)}-${String((index % 12) + 1).padStart(2, "0")}`;
}

/** 형식이 맞고 이번 달 이후가 아니면 true (백엔드도 같은 기준으로 422를 낸다) */
export function isSelectableMonth(month: string | null | undefined, current: string): month is string {
  return !!month && MONTH_PATTERN.test(month) && Number(month.slice(0, 4)) >= 2000 && month <= current;
}

/** 마감은 지난 달부터 할 수 있으므로 기본은 지난 달 */
export function defaultClosingMonth(now: Date = new Date()): string {
  return shiftMonth(currentMonthKst(now), -1);
}

/** "2026-09" → "2026년 9월" */
export function monthLabel(month: string): string {
  const [y, m] = month.split("-").map(Number);
  return `${y}년 ${m}월`;
}

/**
 * 차트 축 눈금: 0부터 max를 덮는 1·2·5 × 10^n 간격 (count개 안팎).
 * 화면 좌표 계산용이라 Number를 쓴다. 금액 계산·표시는 money.ts로 한다.
 */
export function niceTicks(max: number, count = 4): number[] {
  if (!(max > 0)) return [0];
  const raw = max / count;
  const pow = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 5, 10].map((s) => s * pow).find((s) => s >= raw) ?? 10 * pow;
  const ticks: number[] = [];
  for (let v = 0; v < max + step / 2; v += step) ticks.push(v);
  if (ticks[ticks.length - 1] < max) ticks.push(ticks[ticks.length - 1] + step);
  return ticks;
}

/** 축 표기: 12000000 → "1,200만", 0 → "0", 5000 → "0.5만" (만원 미만은 소수 첫째 자리) */
export function formatManWon(value: string | number): string {
  const cents = toCents(typeof value === "number" ? String(Math.round(Math.abs(value))) : value.replace(/^-/, ""));
  const negative = (typeof value === "number" ? value < 0 : value.startsWith("-")) && cents > BigInt(0);
  const tenths = (cents + BigInt(50_000)) / BigInt(100_000); // 만원의 1/10 단위, 반올림
  const whole = tenths / BigInt(10);
  const frac = tenths % BigInt(10);
  if (tenths === BigInt(0)) return "0";
  const grouped = whole.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return `${negative ? "-" : ""}${grouped}${frac === BigInt(0) ? "" : `.${frac}`}만`;
}
