from __future__ import annotations

from app.ocr.models import Line, Segment, group_lines


def lines_of(*rows: str | list[tuple[str, float]], score: float = 0.95) -> list[Line]:
    """테스트용 OCR 줄. 문자열이면 한 조각, [(텍스트, x), ...]이면 같은 줄의 여러 조각."""
    segments: list[Segment] = []
    for i, row in enumerate(rows):
        y = i * 40.0
        parts = [(row, 20.0)] if isinstance(row, str) else row
        for text, x in parts:
            segments.append(Segment(text, score, x, y, x + 12 * len(text), y + 24))
    return group_lines(segments)
