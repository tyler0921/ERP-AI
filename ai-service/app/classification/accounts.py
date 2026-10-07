"""키워드 규칙 기반 계정과목 후보 추천.

AI 판단이 아니라 설명 가능한 규칙이다. 각 후보에 어떤 키워드가 어디서 맞았는지 사유를 붙인다.
최종 결정은 사람이 승인할 때 한다. 계정과목 코드는 회사 기본 계정과목표(Spring AccountService)와 같다.
"""

from __future__ import annotations

from dataclasses import dataclass

# (코드, 이름, 키워드)
RULES: list[tuple[str, str, tuple[str, ...]]] = [
    ("5800", "운반비", ("택배", "퀵", "배송", "화물", "대한통운", "우체국", "로젠", "한진", "용달")),
    ("5400", "광고선전비", ("광고", "네이버광고", "구글", "google", "메타", "facebook", "인스타", "현수막", "전단", "홍보", "판촉")),
    ("5300", "접대비", ("접대", "선물", "골프", "경조", "화환", "축의", "조의", "상품권")),
    ("5200", "복리후생비", ("식당", "식사", "커피", "카페", "스타벅스", "투썸", "이디야", "김밥", "치킨", "피자", "도시락", "회식", "베이커리", "빵", "한식", "중식", "분식", "버거")),
    ("5600", "소모품비", ("문구", "다이소", "사무용품", "토너", "복사지", "a4", "오피스", "잉크", "건전지", "생활용품")),
    ("5700", "지급수수료", ("수수료", "세무", "법무", "회계사", "컨설팅", "구독", "클라우드", "aws", "호스팅", "도메인", "라이선스", "saas")),
    ("5500", "임차료", ("임대", "월세", "임차", "렌탈", "리스", "관리비")),
    ("5900", "외주비", ("외주", "용역", "개발비", "디자인", "제작", "번역")),
]


@dataclass
class Suggestion:
    code: str
    name: str
    confidence: float
    reasons: list[str]

    def to_dict(self) -> dict:
        return {"code": self.code, "name": self.name, "confidence": round(self.confidence, 2), "reasons": self.reasons}


def suggest_accounts(vendor: str | None, items: str | None, full_text: str, limit: int = 3) -> list[dict]:
    """거래처명(가중치 3) > 품목(2) > 전체 텍스트(1) 순으로 키워드를 센다."""
    sources = [("거래처명", (vendor or "").lower(), 3), ("품목", (items or "").lower(), 2), ("본문", full_text.lower(), 1)]
    suggestions: list[Suggestion] = []
    for code, name, keywords in RULES:
        score = 0
        reasons: list[str] = []
        for label, text, weight in sources:
            hits = [k for k in keywords if k in text]
            if hits:
                score += weight * len(hits)
                reasons.append(f"{label}에 '{', '.join(hits[:3])}'")
        if score > 0:
            # 규칙 기반 추천은 과거 승인 이력(0.95)보다 항상 낮게 둔다
            suggestions.append(Suggestion(code, name, min(0.85, 0.35 + 0.1 * score), reasons))
    suggestions.sort(key=lambda s: s.confidence, reverse=True)
    return [s.to_dict() for s in suggestions[:limit]]
