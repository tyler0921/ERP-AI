"""OCR 결과 자료구조. 엔진(PaddleOCR 등)과 추출기 사이의 계약이다."""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True)
class Segment:
    """OCR이 인식한 텍스트 조각 하나와 위치(좌상단 x0,y0 / 우하단 x1,y1)."""

    text: str
    score: float
    x0: float
    y0: float
    x1: float
    y1: float
    page: int = 0

    @property
    def xc(self) -> float:
        return (self.x0 + self.x1) / 2

    @property
    def yc(self) -> float:
        return (self.y0 + self.y1) / 2

    @property
    def height(self) -> float:
        return max(1.0, self.y1 - self.y0)


@dataclass
class Line:
    """같은 높이에 있는 조각들을 왼쪽부터 이은 한 줄."""

    segments: list[Segment] = field(default_factory=list)

    @property
    def text(self) -> str:
        return " ".join(s.text for s in self.segments)

    @property
    def score(self) -> float:
        return min((s.score for s in self.segments), default=0.0)

    @property
    def yc(self) -> float:
        return sum(s.yc for s in self.segments) / max(1, len(self.segments))

    @property
    def page(self) -> int:
        return self.segments[0].page if self.segments else 0


def group_lines(segments: list[Segment]) -> list[Line]:
    """조각을 페이지·세로 위치 순으로 묶어 줄을 만든다.

    세로 중심이 현재 줄 평균 높이의 절반 이내면 같은 줄로 본다.
    """
    lines: list[Line] = []
    for seg in sorted(segments, key=lambda s: (s.page, s.yc, s.x0)):
        current = lines[-1] if lines else None
        if current is not None and current.page == seg.page:
            avg_h = sum(s.height for s in current.segments) / len(current.segments)
            if abs(seg.yc - current.yc) <= avg_h / 2:
                current.segments.append(seg)
                continue
        lines.append(Line([seg]))
    for line in lines:
        line.segments.sort(key=lambda s: s.x0)
    return lines


@dataclass
class OcrResult:
    segments: list[Segment]
    engine: str
    model: str
    duration_ms: int
    pages: int = 1

    @property
    def lines(self) -> list[Line]:
        return group_lines(self.segments)
