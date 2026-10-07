"""업로드 파일(이미지/PDF)을 OCR 입력 이미지 목록으로 바꾼다."""

from __future__ import annotations

import io

from PIL import Image, ImageOps

MAX_SIDE = 2000  # 너무 큰 이미지는 축소 (CPU OCR 속도·메모리)


class UnsupportedFile(ValueError):
    pass


def sniff(content: bytes) -> str:
    """매직 바이트로 형식을 판별한다. 확장자/Content-Type은 믿지 않는다."""
    if content.startswith(b"%PDF"):
        return "pdf"
    if content.startswith(b"\x89PNG\r\n\x1a\n"):
        return "png"
    if content.startswith(b"\xff\xd8\xff"):
        return "jpeg"
    raise UnsupportedFile("PNG, JPEG, PDF 파일만 처리할 수 있습니다.")


def load_pages(content: bytes, max_pdf_pages: int = 3) -> list[Image.Image]:
    kind = sniff(content)
    if kind == "pdf":
        import pypdfium2 as pdfium

        pdf = pdfium.PdfDocument(content)
        try:
            pages = []
            for i in range(min(len(pdf), max_pdf_pages)):
                # 약 200dpi (72dpi × 2.8)
                pages.append(_limit(pdf[i].render(scale=2.8).to_pil()))
            return pages
        finally:
            pdf.close()
    image = Image.open(io.BytesIO(content))
    image = ImageOps.exif_transpose(image)  # 휴대폰 사진의 회전 정보 반영
    return [_limit(image.convert("RGB"))]


def _limit(image: Image.Image) -> Image.Image:
    if max(image.size) <= MAX_SIDE:
        return image
    image = image.copy()
    image.thumbnail((MAX_SIDE, MAX_SIDE))
    return image
