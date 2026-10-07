"""문서 처리 파이프라인: 파일 → OCR → 필드 추출 → 계정과목 후보."""

from __future__ import annotations

from app.classification.accounts import suggest_accounts
from app.config import settings
from app.extraction.receipt import extract_receipt
from app.extraction.tax_invoice import extract_tax_invoice
from app.ocr.engine import OcrEngine
from app.ocr.images import load_pages
from app.ocr.models import Line, OcrResult

DOC_TYPES = ("RECEIPT", "TAX_INVOICE")


def extract_from_lines(lines: list[Line], doc_type: str) -> dict:
    if doc_type not in DOC_TYPES:
        raise ValueError(f"지원하지 않는 문서 유형: {doc_type}")
    extracted = extract_tax_invoice(lines) if doc_type == "TAX_INVOICE" else extract_receipt(lines)
    fields = extracted["fields"]
    full_text = "\n".join(line.text for line in lines)
    extracted["accountSuggestions"] = suggest_accounts(
        fields["vendorName"]["value"], fields["itemsSummary"]["value"], full_text
    )
    return extracted


def process(content: bytes, doc_type: str, engine: OcrEngine) -> dict:
    pages = load_pages(content, settings.max_pdf_pages)
    ocr: OcrResult = engine.recognize(pages)
    lines = ocr.lines
    result = extract_from_lines(lines, doc_type)
    result["ocr"] = {
        "engine": ocr.engine,
        "model": ocr.model,
        "durationMs": ocr.duration_ms,
        "pages": ocr.pages,
        "lines": [{"text": line.text, "score": round(line.score, 3), "page": line.page} for line in lines],
    }
    return result
