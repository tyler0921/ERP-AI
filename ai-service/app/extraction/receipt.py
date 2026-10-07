"""영수증(카드 매출전표·간이영수증) 필드 추출. 규칙 기반이며 계산으로 값을 만들어내지 않는다.

찾지 못한 필드는 비워 두고(confidence 0), Spring의 검증 규칙과 사람이 처리한다.
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
    text_after_label,
)
from app.extraction.patterns import biz_no_checksum_ok, find_amounts, find_biz_nos, find_dates, item_name
from app.ocr.models import Line

TOTAL_LABELS = ["합계금액", "총합계", "결제금액", "받을금액", "청구금액", "승인금액", "총금액", "판매총액", "합계", "총액", "TOTAL"]
VAT_LABELS = ["부가가치세", "부가세", "세액", "VAT"]
SUPPLY_LABELS = ["공급가액", "과세물품가액", "과세금액", "공급가"]
MERCHANT_LABELS = ["가맹점명", "가맹점", "상호명", "상호", "매장명"]
DATE_LABELS = ["거래일시", "거래일자", "결제일시", "승인일시", "판매일", "일시", "날짜"]
BIZ_LABELS = ["사업자번호", "사업자등록번호", "사업자"]

# 상호명 다음에 붙어 나오는 다른 항목 라벨 (값을 자를 위치)
MERCHANT_STOP = re.compile(r"(대표자?|사업자|주소|전화|tel|TEL|☎)")
TITLE_WORDS = ("영수증", "매출전표", "신용카드", "현금영수증", "고객용", "receipt", "카드전표", "가맹점용")
NON_ITEM_WORDS = TOTAL_LABELS + VAT_LABELS + SUPPLY_LABELS + ["면세", "할인", "거스름", "받은금액", "카드", "승인", "할부", "잔액"]


def extract_receipt(lines: list[Line]) -> dict:
    fields: dict[str, FieldValue] = {
        "vendorName": _merchant(lines),
        "vendorBusinessNo": _biz_no(lines),
        "issueDate": _date(lines),
        "totalAmount": _labeled_amount(lines, TOTAL_LABELS, exclude=("부가", "공급", "과세", "면세", "할인"), pick_max=True),
        "vatAmount": _labeled_amount(lines, VAT_LABELS, exclude=("제외", "면세")),
        "supplyAmount": _labeled_amount(lines, SUPPLY_LABELS),
        "paymentMethod": _payment(lines),
        "itemsSummary": _items(lines),
    }
    if fields["totalAmount"].value is None:
        fields["totalAmount"] = _largest_amount(lines)
    return {
        "fields": {k: v.to_dict() for k, v in fields.items()},
        "overallConfidence": overall_confidence(fields, ["vendorName", "issueDate", "totalAmount"]),
    }


def _labeled_amount(lines: list[Line], labels: list[str], exclude: tuple[str, ...] = (), pick_max: bool = False) -> FieldValue:
    candidates: list[tuple[str, Line]] = []
    for i, line in enumerate(lines):
        if label_index(line, labels, exclude) < 0:
            continue
        value = amount_after_label(line, labels)
        if value is None and i + 1 < len(lines):
            # 라벨과 금액이 다음 줄에 나뉘어 인식된 경우
            nxt = find_amounts(lines[i + 1].text)
            value = nxt[-1] if nxt and not re.search(r"[가-힣A-Za-z]{2,}", lines[i + 1].text) else None
        if value is not None:
            candidates.append((value, line))
    if not candidates:
        return MISSING
    value, line = max(candidates, key=lambda c: float(c[0])) if pick_max else candidates[0]
    return found(value, line, LABELED, "라벨 일치")


def _largest_amount(lines: list[Line]) -> FieldValue:
    best: tuple[str, Line] | None = None
    for line in lines:
        for amount in find_amounts(line.text):
            if best is None or float(amount) > float(best[0]):
                best = (amount, line)
    return found(best[0], best[1], HEURISTIC * 0.8, "가장 큰 금액(추정)") if best else MISSING


def _merchant(lines: list[Line]) -> FieldValue:
    for line in lines:
        rest = text_after_label(line, MERCHANT_LABELS)
        if rest is None:
            continue
        value = MERCHANT_STOP.split(rest.strip(" :："))[0].strip(" :：")
        if len(value) >= 2:
            return found(value, line, LABELED, "라벨 일치")
    # 라벨이 없으면 상단의 첫 "이름 같은" 줄
    for line in lines[: max(3, len(lines) // 4)]:
        text = line.text.strip()
        if any(w in text.lower() for w in TITLE_WORDS):
            continue
        letters = len(re.findall(r"[가-힣A-Za-z]", text))
        digits = len(re.findall(r"\d", text))
        if letters >= 2 and digits <= letters:
            return found(text, line, HEURISTIC, "상단 첫 줄(추정)")
    return MISSING


def _biz_no(lines: list[Line]) -> FieldValue:
    fallback: FieldValue | None = None
    for line in lines:
        labeled = label_index(line, BIZ_LABELS) >= 0
        for biz in find_biz_nos(line.text, allow_plain=labeled):
            weight = (LABELED if labeled else HEURISTIC) * (1.0 if biz_no_checksum_ok(biz) else 0.5)
            fv = found(biz, line, weight, "라벨 일치" if labeled else "형식 일치")
            if labeled:
                return fv
            fallback = fallback or fv
    return fallback or MISSING


def _date(lines: list[Line]) -> FieldValue:
    fallback: FieldValue | None = None
    for line in lines:
        dates = find_dates(line.text)
        if not dates:
            continue
        if label_index(line, DATE_LABELS) >= 0:
            return found(dates[0].isoformat(), line, LABELED, "라벨 일치")
        fallback = fallback or found(dates[0].isoformat(), line, HEURISTIC, "첫 날짜(추정)")
    return fallback or MISSING


def _payment(lines: list[Line]) -> FieldValue:
    for line in lines:
        text = line.text
        if re.search(r"(신용|체크)\s*카드|카드\s*(결제|승인)|승인\s*번호|할부", text):
            return found("CARD", line, LABELED, "카드 결제 문구")
        if re.search(r"계좌\s*이체|무통장", text):
            return found("TRANSFER", line, LABELED, "이체 문구")
        if re.search(r"현금", text) and "현금영수증" not in text.replace(" ", ""):
            return found("CASH", line, HEURISTIC, "현금 문구")
    return MISSING


def _items(lines: list[Line]) -> FieldValue:
    """품목 줄(이름 + 금액)을 모아 "A, B 외 n건" 형태로 요약한다."""
    names: list[str] = []
    source: Line | None = None
    for line in lines:
        if label_index(line, NON_ITEM_WORDS) >= 0 or not find_amounts(line.text):
            continue
        name = item_name(line.text)
        if len(re.findall(r"[가-힣A-Za-z]", name)) >= 2 and not find_biz_nos(name) and not find_dates(line.text):
            names.append(name)
            source = source or line
    if not names or source is None:
        return MISSING
    summary = ", ".join(names[:2]) + (f" 외 {len(names) - 2}건" if len(names) > 2 else "")
    return found(summary, source, HEURISTIC, "품목 줄")
