from datetime import date

from app.extraction.patterns import (
    biz_no_checksum_ok,
    find_amounts,
    find_biz_nos,
    find_dates,
    item_name,
    parse_amount,
)
from app.ocr.models import Segment, group_lines


def test_사업자등록번호_체크섬():
    assert biz_no_checksum_ok("123-45-67891")
    assert biz_no_checksum_ok("314-86-12340")
    assert biz_no_checksum_ok("211-88-55554")
    assert not biz_no_checksum_ok("123-45-67890")
    assert not biz_no_checksum_ok("123-45-6789")


def test_금액은_날짜_시간_사업자번호_카드번호와_구분한다():
    text = "2026-10-0814:32 사업자 211-88-55554 카드 1234-56**-****-7890 합계18,000원"
    assert find_amounts(text) == ["18000.00"]
    assert find_amounts("공급가액 16,364 부가세 1,636") == ["16364.00", "1636.00"]


def test_OCR이_공백을_빼먹은_경우도_처리한다():
    assert find_amounts("합계1,253,000원") == ["1253000.00"]
    assert find_dates("2026-10-0814:32") == [date(2026, 10, 8)]
    assert item_name("아메리카노29,000") == "아메리카노"
    assert item_name("카페라떼 1 5,000") == "카페라떼"


def test_날짜_형식():
    assert find_dates("2026.10.07") == [date(2026, 10, 7)]
    assert find_dates("2026년 10월 7일") == [date(2026, 10, 7)]
    assert find_dates("20261007") == [date(2026, 10, 7)]
    assert find_dates("2026-13-40") == []


def test_사업자번호_형식():
    assert find_biz_nos("등록번호 314-86-12340") == ["314-86-12340"]
    assert find_biz_nos("3148612340") == []
    assert find_biz_nos("3148612340", allow_plain=True) == ["314-86-12340"]


def test_금액_파싱():
    assert parse_amount("1,253,000원") == "1253000.00"
    assert parse_amount("0") is None
    assert parse_amount("abc") is None


def test_조각을_줄로_묶는다():
    segs = [
        Segment("합계", 0.9, 10, 100, 60, 124),
        Segment("가상커피", 0.9, 10, 10, 100, 34),
        Segment("18,000", 0.8, 300, 102, 380, 126),
    ]
    lines = group_lines(segs)
    assert [line.text for line in lines] == ["가상커피", "합계 18,000"]
    assert lines[1].score == 0.8
