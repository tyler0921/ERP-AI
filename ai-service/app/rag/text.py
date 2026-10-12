"""회계 규정 문서 → 텍스트 → 청크. 순수 함수 (모델·네트워크 없음).

- 텍스트 추출: PDF(텍스트 레이어), DOCX(word/document.xml 문단, 제목 스타일은 '#'으로), TXT·MD(UTF-8 또는 CP949).
  스캔한 PDF는 텍스트 레이어가 없어 빈 결과가 되고, Spring이 '텍스트를 찾지 못했습니다'로 실패 처리한다.
- 청킹: 문단 단위. 제목(#, 제N장·제N절·제N조)을 만나면 섹션 경로를 갱신하고, 청크는 섹션을 넘지 않는다.
  같은 섹션의 문단을 약 500자까지 모으고, 긴 문단은 문장 경계에서 자른다. 문서 제목(# 한 개)은 경로에서 뺀다.
"""

from __future__ import annotations

import io
import re
import zipfile
from dataclasses import dataclass
from xml.etree import ElementTree

TARGET_CHARS = 500
MAX_PARAGRAPH_CHARS = 800
MAX_PAGES = 300
MAX_DOCX_XML_BYTES = 20 * 1024 * 1024


class UnsupportedDocument(ValueError):
    """읽을 수 없는 문서"""


@dataclass(frozen=True)
class Page:
    number: int | None  # PDF 페이지(1부터). 다른 형식은 None
    text: str


@dataclass(frozen=True)
class Chunk:
    section: str | None
    page: int | None
    content: str

    def embedding_text(self) -> str:
        """임베딩할 문장: 섹션 경로를 앞에 붙여 '어느 조항의 내용인지'도 검색되게 한다"""
        return f"{self.section}\n{self.content}" if self.section else self.content


# ------------------------------------------------------------------ 텍스트 추출


def decode_text(content: bytes) -> str:
    for encoding in ("utf-8-sig", "cp949"):
        try:
            return content.decode(encoding)
        except UnicodeDecodeError:
            continue
    raise UnsupportedDocument("텍스트 인코딩을 알 수 없습니다 (UTF-8 또는 CP949만 지원).")


_W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


def docx_text(content: bytes) -> str:
    """DOCX 본문 문단. 'Heading N'·'제목 N' 스타일 문단은 마크다운 제목('#' N개)으로 바꿔 청킹이 섹션을 알게 한다."""
    try:
        with zipfile.ZipFile(io.BytesIO(content)) as z:
            info = z.getinfo("word/document.xml")
            if info.file_size > MAX_DOCX_XML_BYTES:  # 압축 폭탄 방지
                raise UnsupportedDocument("문서 본문이 너무 큽니다.")
            xml = z.read(info)
    except (zipfile.BadZipFile, KeyError) as e:
        raise UnsupportedDocument("DOCX 파일을 읽을 수 없습니다.") from e
    root = ElementTree.fromstring(xml)
    lines = []
    for p in root.iter(f"{_W}p"):
        text = "".join(t.text or "" for t in p.iter(f"{_W}t")).strip()
        if not text:
            continue
        style = p.find(f"{_W}pPr/{_W}pStyle")
        level = None
        if style is not None:
            m = re.search(r"(?:heading|제목)\s*(\d)", style.get(f"{_W}val", ""), re.IGNORECASE)
            level = int(m[1]) if m else None
        lines.append(("#" * level + " " + text) if level else text)
    return "\n\n".join(lines)


def pdf_pages(content: bytes) -> list[Page]:
    import pypdfium2 as pdfium

    try:
        pdf = pdfium.PdfDocument(content)
    except pdfium.PdfiumError as e:
        raise UnsupportedDocument("PDF 파일을 읽을 수 없습니다.") from e
    try:
        if len(pdf) > MAX_PAGES:
            raise UnsupportedDocument(f"PDF는 {MAX_PAGES}쪽까지 색인할 수 있습니다.")
        pages = []
        for i in range(len(pdf)):
            textpage = pdf[i].get_textpage()
            pages.append(Page(i + 1, textpage.get_text_range().replace("\r\n", "\n").replace("\r", "\n")))
        return pages
    finally:
        pdf.close()


def extract_pages(content: bytes, content_type: str) -> list[Page]:
    if content_type == "application/pdf":
        return pdf_pages(content)
    if content_type.endswith("wordprocessingml.document"):
        return [Page(None, docx_text(content))]
    if content_type in ("text/plain", "text/markdown"):
        return [Page(None, decode_text(content))]
    raise UnsupportedDocument(f"지원하지 않는 형식: {content_type}")


# ------------------------------------------------------------------ 청킹

_MD_HEADING = re.compile(r"^(#{1,6})\s+(.+?)\s*#*$")
# "제2장 접대비", "제3절 …", "제12조(접대비) 본문…" — 조는 제목 뒤에 본문이 같은 줄에 오는 경우가 많다
_KO_HEADING = re.compile(r"^(제\s*\d+\s*(장|절|조)(?:\s*의\s*\d+)?(?:\s*\([^)]{1,40}\))?)\s*(.*)$")
_KO_LEVEL = {"장": 2, "절": 3, "조": 4}
# "제5조에 따라 …"처럼 본문에서 조문을 인용한 줄은 제목이 아니다
_CITATION_TAIL = re.compile(r"^(에|의|를|을|은|는|와|과|및|부터|까지|에서|로|으로|이|가)(\s|$)")


def _ko_heading(line: str) -> re.Match | None:
    m = _KO_HEADING.match(line)
    if m and "(" not in m[1] and _CITATION_TAIL.match(m[3]):
        return None
    return m


_SENTENCE = re.compile(r"(?<=[.!?。])\s+|(?<=다\.)\s*|\n")


def _paragraphs(text: str) -> list[str]:
    """빈 줄로 문단을 나눈다. 제목 줄은 앞뒤에 빈 줄이 없어도 따로 떼어 낸다."""
    out: list[str] = []
    buf: list[str] = []
    for line in text.split("\n"):
        stripped = line.strip()
        if not stripped:
            if buf:
                out.append(" ".join(buf))
                buf = []
            continue
        if _MD_HEADING.match(stripped) or _ko_heading(stripped):
            if buf:
                out.append(" ".join(buf))
                buf = []
            out.append(stripped)
            continue
        buf.append(stripped)
    if buf:
        out.append(" ".join(buf))
    return out


def _split_long(paragraph: str) -> list[str]:
    if len(paragraph) <= MAX_PARAGRAPH_CHARS:
        return [paragraph]
    pieces: list[str] = []
    current = ""
    for sentence in (s.strip() for s in _SENTENCE.split(paragraph)):
        if not sentence:
            continue
        if current and len(current) + 1 + len(sentence) > TARGET_CHARS:
            pieces.append(current)
            current = sentence
        else:
            current = f"{current} {sentence}".strip()
        while len(current) > MAX_PARAGRAPH_CHARS:  # 문장 경계가 없는 아주 긴 문장
            pieces.append(current[:TARGET_CHARS])
            current = current[TARGET_CHARS:]
    if current:
        pieces.append(current)
    return pieces


def chunk_pages(pages: list[Page]) -> list[Chunk]:
    chunks: list[Chunk] = []
    path: dict[int, str] = {}
    current: list[str] = []
    current_page: int | None = None

    def section() -> str | None:
        parts = [path[k] for k in sorted(path) if k > 1]
        return " > ".join(parts) if parts else None

    def flush() -> None:
        nonlocal current, current_page
        if current:
            chunks.append(Chunk(section(), current_page, "\n".join(current)))
        current = []
        current_page = None

    for page in pages:
        for para in _paragraphs(page.text):
            md = _MD_HEADING.match(para)
            ko = None if md else _ko_heading(para)
            if md or ko:
                flush()
                if md:
                    level, title = len(md[1]), md[2].strip()
                    # 마크다운 제목 안의 "제N조" 등은 그 단계로 본다
                    inner = _ko_heading(title)
                    if inner and level > 1:
                        level = max(level, _KO_LEVEL[inner[2]])
                    body = ""
                else:
                    level, title, body = _KO_LEVEL[ko[2]], re.sub(r"\s+", " ", ko[1]).strip(), ko[3].strip()
                    if ko[2] != "조" and body:
                        title, body = f"{title} {body}", ""  # "제2장 접대비"는 줄 전체가 제목
                for k in [k for k in path if k >= level]:
                    del path[k]
                path[level] = title
                if not body:
                    continue
                para = body
            for piece in _split_long(para):
                if current and sum(len(c) for c in current) + len(piece) > TARGET_CHARS:
                    flush()
                if not current:
                    current_page = page.number
                current.append(piece)
    flush()
    return [c for c in chunks if len(c.content.strip()) >= 2]
