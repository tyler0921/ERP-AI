import { describe, expect, it } from "vitest";
import { sourceLabel, splitCitations } from "./rules";

describe("rules", () => {
  it("답변의 [번호] 인용을 나눈다 (범위 밖 번호는 글로 둔다)", () => {
    expect(splitCitations("한도는 300,000원입니다 [1]. 넘으면 승인 [2][9].", 2)).toEqual([
      { text: "한도는 300,000원입니다 " },
      { cite: 1 },
      { text: ". 넘으면 승인 " },
      { cite: 2 },
      { text: "[9]." },
    ]);
    expect(splitCitations("인용 없음", 3)).toEqual([{ text: "인용 없음" }]);
    expect(splitCitations("[1]", 1)).toEqual([{ cite: 1 }]);
  });

  it("출처 표기", () => {
    expect(sourceLabel("경비 처리 규정", "제2장 접대비 > 제5조(접대비 한도)", 3)).toBe("경비 처리 규정 › 제2장 접대비 > 제5조(접대비 한도) (p.3)");
    expect(sourceLabel("계정과목 가이드", null, null)).toBe("계정과목 가이드");
  });
});
