"""실제 PaddleOCR로 가상 샘플 이미지를 읽어 전체 파이프라인을 검증한다.

PaddleOCR과 한글 폰트가 있는 환경(ai-service 컨테이너)에서만 실행된다.
"""

import io
import importlib.util
from pathlib import Path

import pytest

pytestmark = pytest.mark.skipif(
    importlib.util.find_spec("paddleocr") is None or not Path("/usr/share/fonts/truetype/nanum").exists(),
    reason="PaddleOCR/한글 폰트가 없는 환경",
)


@pytest.fixture(scope="module")
def engine():
    from app.ocr.engine import PaddleOcrEngine

    return PaddleOcrEngine()


def to_bytes(image, fmt="PNG") -> bytes:
    buf = io.BytesIO()
    image.save(buf, fmt)
    return buf.getvalue()


def values(result: dict) -> dict:
    return {k: v["value"] for k, v in result["fields"].items()}


def test_영수증_이미지(engine):
    from app.pipeline import process
    from scripts.make_samples import receipt

    result = process(to_bytes(receipt()), "RECEIPT", engine)
    v = values(result)
    assert v["vendorBusinessNo"] == "211-88-55554"
    assert v["issueDate"] == "2026-10-07"
    assert v["totalAmount"] == "18000.00"
    assert v["supplyAmount"] == "16364.00"
    assert v["vatAmount"] == "1636.00"
    assert "가상커피" in (v["vendorName"] or "")
    assert result["accountSuggestions"][0]["code"] == "5200"
    assert result["ocr"]["lines"]


def test_세금계산서_PDF(engine):
    from app.pipeline import process
    from scripts.make_samples import tax_invoice

    result = process(to_bytes(tax_invoice(), "PDF"), "TAX_INVOICE", engine)
    v = values(result)
    assert v["vendorBusinessNo"] == "314-86-12340"
    assert v["buyerBusinessNo"] == "123-45-67891"
    assert v["issueDate"] == "2026-10-05"
    assert v["supplyAmount"] == "3000000.00"
    assert v["vatAmount"] == "300000.00"
    assert v["totalAmount"] == "3300000.00"
