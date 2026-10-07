from app.classification.accounts import suggest_accounts
from app.extraction.receipt import extract_receipt
from app.extraction.tax_invoice import extract_tax_invoice
from app.pipeline import extract_from_lines

from tests.helpers import lines_of


def values(result: dict) -> dict:
    return {k: v["value"] for k, v in result["fields"].items()}


def test_카드영수증_필드를_라벨로_찾는다():
    lines = lines_of(
        "카드 영수증 (고객용)",
        "상호: 가상커피 강남점",
        "사업자번호: 211-88-55554",
        "대표: 김가상 TEL 02-123-4567",
        "거래일시: 2026-10-07 12:34:56",
        "아메리카노 2 9,000",
        "카페라떼 1 5,000",
        "베이글 1 4,000",
        "공급가액 16,364",
        "부가세 1,636",
        "합계금액 18,000",
        "신용카드 승인 일시불",
        "카드번호 1234-56**-****-7890",
    )
    result = extract_receipt(lines)
    v = values(result)
    assert v["vendorName"] == "가상커피 강남점"
    assert v["vendorBusinessNo"] == "211-88-55554"
    assert v["issueDate"] == "2026-10-07"
    assert v["supplyAmount"] == "16364.00"
    assert v["vatAmount"] == "1636.00"
    assert v["totalAmount"] == "18000.00"
    assert v["paymentMethod"] == "CARD"
    assert v["itemsSummary"] == "아메리카노, 카페라떼 외 1건"
    assert result["fields"]["totalAmount"]["source"].startswith("라벨 일치")
    assert result["overallConfidence"] > 0.9


def test_라벨이_없으면_추정하고_신뢰도를_낮춘다():
    result = extract_receipt(lines_of("다이소 역삼점", "2026.10.01", "건전지 3,000", "A4용지 5,000", "8,000"))
    v = values(result)
    assert v["vendorName"] == "다이소 역삼점"
    assert v["totalAmount"] == "8000.00"
    assert result["fields"]["totalAmount"]["confidence"] < 0.6
    assert result["fields"]["vatAmount"]["value"] is None  # 없는 값은 만들지 않는다


def test_합계_라벨이_여러개면_가장_큰_금액():
    v = values(extract_receipt(lines_of("상호 가상마트", "합계 10,000", "할인 합계 1,000", "총 결제금액 9,000")))
    assert v["totalAmount"] == "10000.00"


def test_세금계산서_표_양식():
    lines = lines_of(
        "전자세금계산서",
        [("공급자", 40), ("공급받는자", 580)],
        [("등록번호", 40), ("314-86-12340", 210), ("등록번호", 580), ("123-45-67891", 750)],
        [("상호", 40), ("(주)가상소프트", 210), ("상호", 580), ("가상상사", 750)],
        [("성명", 40), ("이가상", 210), ("성명", 580), ("박데모", 750)],
        [("작성일자", 60), ("공급가액", 430), ("세액", 800)],
        [("2026-10-05", 60), ("3,000,000", 430), ("300,000", 800)],
        "월 일 품목 수량 공급가액 세액",
        "10 05 소프트웨어 유지보수 1 3,000,000 300,000",
        [("합계금액", 40), ("3,300,000", 240)],
    )
    result = extract_tax_invoice(lines)
    v = values(result)
    assert v["vendorBusinessNo"] == "314-86-12340"
    assert v["buyerBusinessNo"] == "123-45-67891"
    assert v["vendorName"] == "(주)가상소프트"
    assert v["buyerName"] == "가상상사"
    assert v["issueDate"] == "2026-10-05"
    assert v["supplyAmount"] == "3000000.00"
    assert v["vatAmount"] == "300000.00"
    assert v["totalAmount"] == "3300000.00"
    assert v["itemsSummary"] == "소프트웨어 유지보수"
    assert result["overallConfidence"] > 0.8


def test_계정과목_후보는_키워드와_사유를_돌려준다():
    suggestions = suggest_accounts("CJ대한통운", "택배 운임", "택배 운임 합계 5,000")
    assert suggestions[0]["code"] == "5800"
    assert any("거래처명" in r for r in suggestions[0]["reasons"])
    assert suggestions[0]["confidence"] <= 0.85
    assert suggest_accounts("알수없음", None, "") == []


def test_파이프라인은_계정과목_후보를_붙인다():
    result = extract_from_lines(lines_of("상호: 가상커피", "아메리카노 4,500", "합계 4,500"), "RECEIPT")
    assert result["accountSuggestions"][0]["code"] == "5200"
