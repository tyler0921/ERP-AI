import { describe, expect, it } from "vitest";
import { checkJournalLines, toLineRequests } from "./journal";

describe("checkJournalLines", () => {
  it("1:N 전표(매입대금 + 송금수수료)의 균형을 확인한다", () => {
    const result = checkJournalLines([
      { accountId: "10", debit: "1,250,000", credit: "" },
      { accountId: "11", debit: "3,000", credit: "" },
      { accountId: "2", debit: "", credit: "1,253,000" },
    ]);
    expect(result.balanced).toBe(true);
    expect(result.errors).toEqual([]);
    expect(result.totalDebit).toBe("1253000.00");
  });

  it("차액을 계산하고 불균형 오류를 낸다", () => {
    const result = checkJournalLines([
      { accountId: "1", debit: "100", credit: "" },
      { accountId: "2", debit: "", credit: "90" },
    ]);
    expect(result.balanced).toBe(false);
    expect(result.difference).toBe("10.00");
    expect(result.errors).toContain("차변 합계와 대변 합계가 일치해야 합니다.");
  });

  it("한 라인에 차변과 대변을 모두 입력하면 오류", () => {
    const result = checkJournalLines([
      { accountId: "1", debit: "100", credit: "100" },
      { accountId: "2", debit: "", credit: "" },
    ]);
    expect(result.errors.some((e) => e.includes("하나만"))).toBe(true);
  });

  it("빈 줄은 무시하고, 유효 라인이 2개 미만이면 오류", () => {
    const result = checkJournalLines([
      { accountId: "1", debit: "100", credit: "" },
      { accountId: "", debit: "", credit: "" },
    ]);
    expect(result.errors).toContain("전표 라인은 2개 이상이어야 합니다.");
  });

  it("계정과목 누락과 금액 형식 오류를 행 번호와 함께 알려준다", () => {
    const result = checkJournalLines([
      { accountId: "", debit: "100", credit: "" },
      { accountId: "2", debit: "", credit: "1.234" },
    ]);
    expect(result.errors).toContain("1행: 계정과목을 선택하세요.");
    expect(result.errors).toContain("2행: 대변 금액 형식이 올바르지 않습니다.");
  });
});

describe("toLineRequests", () => {
  it("빈 줄을 제외하고 API 형식으로 변환한다", () => {
    expect(
      toLineRequests([
        { accountId: "1", debit: "1,000", credit: "" },
        { accountId: "", debit: "", credit: "" },
        { accountId: "2", debit: "", credit: "1000" },
      ]),
    ).toEqual([
      { accountId: 1, debit: "1000.00", credit: "0.00" },
      { accountId: 2, debit: "0.00", credit: "1000.00" },
    ]);
  });
});
