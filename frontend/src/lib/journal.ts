import { fromCents, normalizeMoneyInput, toCents } from "./money";

// 전표 입력 폼의 클라이언트 측 검증.
// 최종 판단은 백엔드(JournalEntryValidator + DB 제약 트리거)가 하고, 여기서는 사용자에게 즉시 피드백만 준다.

export interface LineDraft {
  accountId: string;
  debit: string;
  credit: string;
}

export interface JournalCheck {
  totalDebit: string;
  totalCredit: string;
  difference: string;
  balanced: boolean;
  errors: string[];
}

export function checkJournalLines(lines: LineDraft[]): JournalCheck {
  const errors: string[] = [];
  let debit = BigInt(0);
  let credit = BigInt(0);
  let filled = 0;

  lines.forEach((line, i) => {
    const n = i + 1;
    const hasDebit = line.debit.trim() !== "";
    const hasCredit = line.credit.trim() !== "";
    if (!line.accountId && !hasDebit && !hasCredit) return; // 빈 줄은 무시
    filled++;
    if (!line.accountId) errors.push(`${n}행: 계정과목을 선택하세요.`);
    if (hasDebit && normalizeMoneyInput(line.debit) === null) errors.push(`${n}행: 차변 금액 형식이 올바르지 않습니다.`);
    if (hasCredit && normalizeMoneyInput(line.credit) === null) errors.push(`${n}행: 대변 금액 형식이 올바르지 않습니다.`);
    const d = toCents(line.debit);
    const c = toCents(line.credit);
    if (d > BigInt(0) && c > BigInt(0)) errors.push(`${n}행: 차변과 대변 중 하나만 입력하세요.`);
    if (d === BigInt(0) && c === BigInt(0)) errors.push(`${n}행: 금액을 입력하세요.`);
    debit += d;
    credit += c;
  });

  if (filled < 2) errors.push("전표 라인은 2개 이상이어야 합니다.");
  const balanced = debit === credit && debit > BigInt(0);
  if (!balanced && filled >= 2) errors.push("차변 합계와 대변 합계가 일치해야 합니다.");

  return {
    totalDebit: fromCents(debit),
    totalCredit: fromCents(credit),
    difference: fromCents(debit - credit),
    balanced,
    errors,
  };
}

/** 폼 값을 API 요청 형식으로 변환한다. 빈 줄은 제외한다. */
export function toLineRequests(lines: LineDraft[]) {
  return lines
    .filter((l) => l.accountId || l.debit.trim() || l.credit.trim())
    .map((l) => ({
      accountId: Number(l.accountId),
      debit: normalizeMoneyInput(l.debit) ?? "0.00",
      credit: normalizeMoneyInput(l.credit) ?? "0.00",
    }));
}
