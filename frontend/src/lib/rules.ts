// 회계 규정 화면 유틸: 표시 이름, 출처 표기, 답변의 [번호] 인용 나누기

import type { RuleCategory, RuleDocumentStatus } from "./types";

export const RULE_CATEGORY_LABEL: Record<RuleCategory, string> = {
  EXPENSE_POLICY: "경비 처리 규정",
  CORPORATE_CARD: "법인카드 사용 규정",
  ACCOUNT_GUIDE: "계정과목 가이드",
  CASE: "회계 처리 사례",
  APPROVAL: "내부 승인 규정",
  OTHER: "기타",
};

export const RULE_STATUS_LABEL: Record<RuleDocumentStatus, string> = {
  PENDING: "색인 중",
  INDEXED: "검색 가능",
  FAILED: "색인 실패",
  ARCHIVED: "보관",
};

export const RULE_EXAMPLES = ["접대비 한도는 얼마인가요?", "법인카드를 주말에 써도 되나요?", "300만원 지출은 누가 승인하나요?", "출장 숙박비 한도는?"];

/** "경비 처리 규정 › 제2장 접대비 > 제5조(접대비 한도) (p.3)" — 백엔드 RagText.sourceLabel과 같은 표기 */
export function sourceLabel(title: string, section: string | null, page: number | null): string {
  return `${title}${section ? ` › ${section}` : ""}${page ? ` (p.${page})` : ""}`;
}

export type CitationPart = { text: string } | { cite: number };

/** 답변 문장을 글과 [번호] 인용으로 나눈다. 발췌 범위 밖 번호는 글로 둔다 */
export function splitCitations(text: string, sourceCount: number): CitationPart[] {
  const parts: CitationPart[] = [];
  let last = 0;
  for (const m of text.matchAll(/\[(\d{1,2})\]/g)) {
    const n = Number(m[1]);
    if (n < 1 || n > sourceCount) continue;
    if (m.index > last) parts.push({ text: text.slice(last, m.index) });
    parts.push({ cite: n });
    last = m.index + m[0].length;
  }
  if (last < text.length) parts.push({ text: text.slice(last) });
  return parts;
}
