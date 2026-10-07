import { describe, expect, it } from "vitest";
import { formatMoney, fromCents, normalizeMoneyInput, sumMoney, toCents } from "./money";

describe("normalizeMoneyInput", () => {
  it("쉼표와 공백을 제거하고 소수점 둘째 자리로 맞춘다", () => {
    expect(normalizeMoneyInput("1,250,000")).toBe("1250000.00");
    expect(normalizeMoneyInput(" 3000.5 ")).toBe("3000.50");
    expect(normalizeMoneyInput("007")).toBe("7.00");
  });

  it("잘못된 형식은 null", () => {
    expect(normalizeMoneyInput("")).toBeNull();
    expect(normalizeMoneyInput("-100")).toBeNull();
    expect(normalizeMoneyInput("1.234")).toBeNull();
    expect(normalizeMoneyInput("1e5")).toBeNull();
    expect(normalizeMoneyInput("abc")).toBeNull();
    expect(normalizeMoneyInput("123456789012345678")).toBeNull(); // 정수부 17자리 초과
  });
});

describe("cents 변환", () => {
  it("부동소수점 오차 없이 합산한다", () => {
    // Number로 계산하면 0.1 + 0.2 = 0.30000000000000004
    expect(sumMoney(["0.10", "0.20"])).toBe("0.30");
    expect(sumMoney(["1250000.00", "3000.00"])).toBe("1253000.00");
  });

  it("Number 정밀도를 넘는 큰 금액도 정확하다", () => {
    expect(sumMoney(["99999999999999999.99", "0.01"])).toBe("100000000000000000.00");
  });

  it("음수 차액을 표현한다", () => {
    expect(fromCents(toCents("90") - toCents("100"))).toBe("-10.00");
  });
});

describe("formatMoney", () => {
  it("천 단위 구분 기호를 넣고 .00은 생략한다", () => {
    expect(formatMoney("1253000.00")).toBe("1,253,000");
    expect(formatMoney("1250.50")).toBe("1,250.50");
    expect(formatMoney("-10.00")).toBe("-10");
    expect(formatMoney(null)).toBe("-");
  });
});
