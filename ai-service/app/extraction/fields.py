"""추출 결과 자료구조와 공통 라벨 탐색 도구."""

from __future__ import annotations

import re
from dataclasses import asdict, dataclass

from app.extraction.patterns import find_amounts, normalize_label
from app.ocr.models import Line, Segment

# 라벨로 찾은 값과 위치 추정으로 찾은 값의 confidence 가중치
LABELED = 1.0
HEURISTIC = 0.6


@dataclass
class FieldValue:
    """필드 하나의 값, 신뢰도(0~1), 근거(어떤 줄·규칙에서 찾았는지)."""

    value: str | None
    confidence: float
    source: str

    def to_dict(self) -> dict:
        d = asdict(self)
        d["confidence"] = round(self.confidence, 3)
        return d


MISSING = FieldValue(None, 0.0, "찾지 못함")


def found(value: str, line: Line, weight: float, rule: str) -> FieldValue:
    return FieldValue(value, max(0.0, min(1.0, line.score * weight)), f"{rule}: \"{line.text}\"")


def label_index(line: Line, labels: list[str], exclude: tuple[str, ...] = ()) -> int:
    """줄에서 라벨이 시작되는 글자 위치(정규화 텍스트 기준). 없으면 -1."""
    norm = normalize_label(line.text)
    if any(normalize_label(x) in norm for x in exclude):
        return -1
    for label in labels:
        idx = norm.find(normalize_label(label))
        if idx >= 0:
            return idx
    return -1


def label_regex(label: str) -> re.Pattern[str]:
    """OCR이 글자 사이에 공백을 넣는 경우("합 계")도 찾도록 글자 사이 공백을 허용한다."""
    chars = [re.escape(c) for c in label if not c.isspace()]
    return re.compile(r"\s*".join(chars), re.IGNORECASE)


def text_after_label(line: Line, labels: list[str]) -> str | None:
    for label in labels:
        m = label_regex(label).search(line.text)
        if m:
            return line.text[m.end():]
    return None


def amount_after_label(line: Line, labels: list[str]) -> str | None:
    """라벨이 있는 줄에서 라벨 뒤에 나오는 마지막 금액. (라벨 앞의 숫자는 제외)"""
    rest = text_after_label(line, labels)
    if rest is None:
        return None
    amounts = find_amounts(rest)
    return amounts[-1] if amounts else None


def value_below(label_seg: Segment, lines: list[Line], start: int, max_lines: int = 3) -> tuple[str, Line] | None:
    """표 형태: 라벨 아래 몇 줄 안에서 x 위치가 가장 가까운 금액 조각을 찾는다."""
    best: tuple[float, str, Line] | None = None
    for line in lines[start + 1 : start + 1 + max_lines]:
        for seg in line.segments:
            amounts = find_amounts(seg.text)
            if not amounts:
                continue
            distance = abs(seg.xc - label_seg.xc)
            if best is None or distance < best[0]:
                best = (distance, amounts[-1], line)
    if best is None:
        return None
    width = max(1.0, label_seg.x1 - label_seg.x0)
    # 라벨에서 너무 멀리 떨어진 값은 다른 컬럼일 가능성이 크다
    if best[0] > width * 3:
        return None
    return best[1], best[2]


def segment_with_label(line: Line, label: str) -> Segment | None:
    target = normalize_label(label)
    for seg in line.segments:
        if target in normalize_label(seg.text):
            return seg
    return None


def overall_confidence(fields: dict[str, FieldValue], required: list[str]) -> float:
    if not required:
        return 0.0
    return round(sum(fields[name].confidence for name in required) / len(required), 3)
