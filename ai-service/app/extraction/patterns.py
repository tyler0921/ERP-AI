"""한국 영수증·세금계산서에서 금액, 날짜, 사업자등록번호를 찾는 정규식과 파서.

금액은 문자열(소수점 둘째 자리)로 다룬다. float로 계산하지 않는다.
"""

from __future__ import annotations

import re
from datetime import date
from decimal import Decimal, InvalidOperation

BIZ_NO_RE = re.compile(r"(?<!\d)(\d{3})\s*-\s*(\d{2})\s*-\s*(\d{5})(?!\d)")
BIZ_NO_PLAIN_RE = re.compile(r"(?<!\d)(\d{10})(?!\d)")

DATE_RE = re.compile(
    r"(?<!\d)(20\d{2})\s*(?:[.\-/]|년)\s*(\d{1,2})\s*(?:[.\-/]|월)\s*(\d{1,2})\s*일?"
)
DATE_COMPACT_RE = re.compile(r"(?<!\d)(20\d{2})(\d{2})(\d{2})(?!\d)")

# 시간, 전화번호, 카드번호처럼 금액으로 오인되기 쉬운 숫자 패턴
TIME_RE = re.compile(r"(?<!\d)\d{1,2}:\d{2}(?::\d{2})?(?!\d)")
PHONE_RE = re.compile(r"(?<!\d)0\d{1,2}[-)\s]\d{3,4}-\d{4}(?!\d)")
CARD_RE = re.compile(r"\d{4}[-\s*]{1,3}[\d*]{2,4}[-\s*]{1,3}[\d*]{2,4}[-\s*]{1,3}[\d*]{2,4}")

AMOUNT_RE = re.compile(r"(?<![\d.,])(-?\d{1,3}(?:,\d{3})+|-?\d{3,12})(?:\.(\d{1,2}))?(?![\d,])")


def normalize_label(text: str) -> str:
    """라벨 비교용: 공백·기호 제거, 소문자."""
    return re.sub(r"[\s:：()\[\]·.\-_/]", "", text).lower()


def strip_non_amount_numbers(text: str) -> str:
    """날짜·시간·사업자번호·전화·카드번호를 지워 금액 탐색에서 제외한다."""
    for pattern in (CARD_RE, DATE_RE, DATE_COMPACT_RE, BIZ_NO_RE, PHONE_RE, TIME_RE):
        text = pattern.sub(" ", text)
    return text


def find_amounts(text: str) -> list[str]:
    """텍스트 안의 금액 후보를 등장 순서대로 "1250000.00" 형식으로 돌려준다."""
    result = []
    for m in AMOUNT_RE.finditer(strip_non_amount_numbers(text)):
        value = parse_amount(m.group(0))
        if value is not None:
            result.append(value)
    return result


ITEM_NUMBER_RE = re.compile(r"\d{1,3}(?:,\d{3})+\s*원?|(?<![가-힣A-Za-z])\d+\s*(?:개|ea|EA)?(?=\s|$)")


def item_name(text: str) -> str:
    """품목 줄에서 금액·수량을 지운 이름. OCR이 공백을 빼먹어도("아메리카노24,500") 동작한다."""
    return re.sub(r"\s{2,}", " ", ITEM_NUMBER_RE.sub(" ", text)).strip(" :x×*")


def parse_amount(text: str) -> str | None:
    cleaned = re.sub(r"[,\s원₩]", "", text)
    if not cleaned:
        return None
    try:
        value = Decimal(cleaned)
    except InvalidOperation:
        return None
    if value <= 0 or value >= Decimal("1e17"):
        return None
    return f"{value.quantize(Decimal('0.01'))}"


def find_dates(text: str) -> list[date]:
    found: list[date] = []
    for pattern in (DATE_RE, DATE_COMPACT_RE):
        for m in pattern.finditer(text):
            try:
                found.append(date(int(m.group(1)), int(m.group(2)), int(m.group(3))))
            except ValueError:
                continue
    return found


def find_biz_nos(text: str, allow_plain: bool = False) -> list[str]:
    """사업자등록번호 후보를 "123-45-67890" 형식으로. allow_plain이면 하이픈 없는 10자리도 허용."""
    result = [f"{a}-{b}-{c}" for a, b, c in BIZ_NO_RE.findall(text)]
    if allow_plain:
        for (digits,) in (m.groups() for m in BIZ_NO_PLAIN_RE.finditer(text)):
            result.append(f"{digits[:3]}-{digits[3:5]}-{digits[5:]}")
    return result


def biz_no_checksum_ok(biz_no: str) -> bool:
    """국세청 사업자등록번호 검증 알고리즘."""
    digits = [int(c) for c in biz_no if c.isdigit()]
    if len(digits) != 10:
        return False
    weights = [1, 3, 7, 1, 3, 7, 1, 3, 5]
    total = sum(d * w for d, w in zip(digits[:9], weights))
    total += (digits[8] * 5) // 10
    return (10 - total % 10) % 10 == digits[9]
