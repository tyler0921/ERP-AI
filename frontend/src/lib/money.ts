// 금액 유틸. 화면에서도 회계 금액을 부동소수점(Number)으로 계산하지 않는다.
// 모든 합산은 "원 단위 × 100"(전, cents)의 BigInt로 수행한다.

const MONEY_PATTERN = /^\d{1,17}(\.\d{1,2})?$/;

/** 사용자가 입력한 금액 문자열을 정규화한다. 쉼표·공백 허용. 형식이 틀리면 null. */
export function normalizeMoneyInput(input: string): string | null {
  const cleaned = input.replace(/[,\s]/g, "");
  if (cleaned === "") return null;
  if (!MONEY_PATTERN.test(cleaned)) return null;
  const [int, frac = ""] = cleaned.split(".");
  return `${BigInt(int).toString()}.${frac.padEnd(2, "0")}`;
}

/** "1250000.50" → 125000050n. 빈 값/잘못된 값은 0n. */
export function toCents(value: string | null | undefined): bigint {
  if (!value) return BigInt(0);
  const normalized = normalizeMoneyInput(value);
  if (normalized === null) return BigInt(0);
  const [int, frac] = normalized.split(".");
  return BigInt(int) * BigInt(100) + BigInt(frac);
}

/** 125000050n → "1250000.50" */
export function fromCents(cents: bigint): string {
  const negative = cents < BigInt(0);
  const abs = negative ? -cents : cents;
  const int = abs / BigInt(100);
  const frac = (abs % BigInt(100)).toString().padStart(2, "0");
  return `${negative ? "-" : ""}${int.toString()}.${frac}`;
}

export function sumMoney(values: (string | null | undefined)[]): string {
  return fromCents(values.reduce((acc, v) => acc + toCents(v), BigInt(0)));
}

/** "1250000.00" → "1,250,000", "1250000.50" → "1,250,000.50" */
export function formatMoney(value: string | null | undefined): string {
  if (value === null || value === undefined || value === "") return "-";
  const negative = value.startsWith("-");
  const raw = negative ? value.slice(1) : value;
  const [int, frac = ""] = raw.split(".");
  const grouped = int.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  const fracPart = frac && /[1-9]/.test(frac) ? `.${frac.padEnd(2, "0").slice(0, 2)}` : "";
  return `${negative ? "-" : ""}${grouped}${fracPart}`;
}
