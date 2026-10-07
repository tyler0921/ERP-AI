"""(전자)세금계산서 필드 추출.

표준 양식은 왼쪽이 공급자, 오른쪽이 공급받는자이고, 공급가액·세액·합계금액은 표 머리글 아래 칸에 있다.
그래서 같은 줄의 라벨 뒤 값 → 라벨 바로 아래 칸의 값(x 위치 기준) 순서로 찾는다.
"""

from __future__ import annotations

import re

from app.extraction.fields import (
    HEURISTIC,
    LABELED,
    MISSING,
    FieldValue,
    amount_after_label,
    found,
    label_index,
    overall_confidence,
    segment_with_label,
    value_below,
)
from app.extraction.patterns import biz_no_checksum_ok, find_biz_nos, find_dates, item_name, normalize_label
from app.ocr.models import Line

SUPPLY_LABELS = ["공급가액"]
VAT_LABELS = ["세액"]
TOTAL_LABELS = ["합계금액", "합계"]
DATE_LABELS = ["작성일자", "작성일", "발행일"]
NAME_LABELS = ["상호", "법인명"]
NAME_STOP = re.compile(r"(성명|대표|사업장|주소|업태|종목|등록번호|이메일)")


def extract_tax_invoice(lines: list[Line]) -> dict:
    page_mid = _page_mid(lines)
    supplier_no, buyer_no = _biz_nos(lines, page_mid)
    supplier_name, buyer_name = _names(lines, page_mid)
    fields: dict[str, FieldValue] = {
        "vendorName": supplier_name,
        "vendorBusinessNo": supplier_no,
        "buyerName": buyer_name,
        "buyerBusinessNo": buyer_no,
        "issueDate": _date(lines),
        "supplyAmount": _amount(lines, SUPPLY_LABELS, exclude=()),
        "vatAmount": _amount(lines, VAT_LABELS, exclude=("부가세포함",)),
        "totalAmount": _amount(lines, TOTAL_LABELS, exclude=()),
        "paymentMethod": MISSING,
        "itemsSummary": _items(lines),
    }
    required = ["vendorName", "vendorBusinessNo", "buyerBusinessNo", "issueDate", "supplyAmount", "vatAmount", "totalAmount"]
    return {
        "fields": {k: v.to_dict() for k, v in fields.items()},
        "overallConfidence": overall_confidence(fields, required),
    }


def _page_mid(lines: list[Line]) -> float:
    xs = [s.x1 for line in lines for s in line.segments]
    return (max(xs) / 2) if xs else 0.0


def _biz_nos(lines: list[Line], page_mid: float) -> tuple[FieldValue, FieldValue]:
    """등록번호 두 개를 x 위치로 공급자(왼쪽)/공급받는자(오른쪽)에 배정한다."""
    found_nos: list[tuple[float, float, str, Line]] = []
    for line in lines:
        for seg in line.segments:
            for biz in find_biz_nos(seg.text):
                found_nos.append((seg.xc, line.yc, biz, line))
    if not found_nos:
        return MISSING, MISSING

    def to_field(item: tuple[float, float, str, Line]) -> FieldValue:
        weight = LABELED if biz_no_checksum_ok(item[2]) else LABELED * 0.5
        return found(item[2], item[3], weight, "등록번호 위치")

    left = [n for n in found_nos if n[0] < page_mid]
    right = [n for n in found_nos if n[0] >= page_mid]
    if left and right:
        return to_field(left[0]), to_field(right[0])
    # 세로로 배치된 양식: 위가 공급자
    ordered = sorted(found_nos, key=lambda n: n[1])
    return to_field(ordered[0]), (to_field(ordered[1]) if len(ordered) > 1 else MISSING)


def _names(lines: list[Line], page_mid: float) -> tuple[FieldValue, FieldValue]:
    left: FieldValue = MISSING
    right: FieldValue = MISSING
    for line in lines:
        segs = line.segments
        for i, seg in enumerate(segs):
            norm = normalize_label(seg.text)
            label = next((l for l in NAME_LABELS if l in norm), None)
            if label is None:
                continue
            # 같은 조각 안의 라벨 뒤 텍스트, 없으면 오른쪽 다음 조각
            after = seg.text.split(label, 1)[-1] if label in seg.text else ""
            after = re.sub(r"^\s*(\(법인명\))?\s*[:：]?\s*", "", after)
            value = after or (segs[i + 1].text if i + 1 < len(segs) else "")
            value = NAME_STOP.split(value)[0].strip(" :：")
            if len(value) < 2:
                continue
            fv = found(value, line, LABELED, "상호 라벨")
            if seg.xc < page_mid and left is MISSING:
                left = fv
            elif seg.xc >= page_mid and right is MISSING:
                right = fv
    return left, right


def _date(lines: list[Line]) -> FieldValue:
    for i, line in enumerate(lines):
        if label_index(line, DATE_LABELS) < 0:
            continue
        for candidate in lines[i : i + 3]:
            dates = find_dates(candidate.text)
            if dates:
                return found(dates[0].isoformat(), candidate, LABELED, "작성일자")
    for line in lines:
        dates = find_dates(line.text)
        if dates:
            return found(dates[0].isoformat(), line, HEURISTIC, "첫 날짜(추정)")
    return MISSING


def _amount(lines: list[Line], labels: list[str], exclude: tuple[str, ...]) -> FieldValue:
    for i, line in enumerate(lines):
        if label_index(line, labels, exclude) < 0:
            continue
        value = amount_after_label(line, labels)
        if value is not None:
            return found(value, line, LABELED, "라벨 일치")
        for label in labels:
            seg = segment_with_label(line, label)
            if seg is None:
                continue
            below = value_below(seg, lines, i)
            if below is not None:
                return found(below[0], below[1], LABELED * 0.9, f"'{label}' 칸 아래 값")
    return MISSING


def _items(lines: list[Line]) -> FieldValue:
    for i, line in enumerate(lines):
        if label_index(line, ["품목"]) >= 0 and i + 1 < len(lines):
            # 품목 줄은 보통 "월 일 품목 규격 수량 단가 공급가액 세액" 순서: 앞의 월·일 숫자를 지운다
            name = item_name(re.sub(r"^\s*\d{1,2}\s+\d{1,2}\s+", "", lines[i + 1].text))
            if len(re.findall(r"[가-힣A-Za-z]", name)) >= 2:
                return found(name, lines[i + 1], HEURISTIC, "품목 아래 줄")
    return MISSING
