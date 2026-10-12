"""회계 규정 RAG: 텍스트 추출(MD·TXT·CP949·DOCX·PDF), 청킹(섹션 경로·문단·긴 문단), 색인 worker, 규정 답변 검증·템플릿.
실제 임베딩 모델 없이 가짜 Embedder로 확인한다 (실제 모델은 컨테이너에서 확인)."""

import io
import json
import zipfile

import pytest

from app.llm.explain import NOT_FOUND_ANSWER, explain, fallback, validate
from app.llm.provider import LlmError
from app.rag.text import (
    MAX_PARAGRAPH_CHARS,
    Chunk,
    Page,
    UnsupportedDocument,
    chunk_pages,
    decode_text,
    docx_text,
    extract_pages,
    pdf_pages,
)
from app.rag.worker import handle_job, index_document
from tests.test_llm import FakeProvider

MARKDOWN = """# 경비 처리 규정 (가상)

이 문서는 데모용 가상 규정이다.

## 제2장 접대비

### 제5조(접대비 한도)

접대비는 거래처 1회당 300,000원을 한도로 집행한다. 한도를 넘는 접대는 부서장의 사전 승인을 받아야 한다.

1인당 접대 금액은 100,000원을 넘을 수 없다.

### 제6조(접대비 증빙)

접대비는 법인카드 매출전표 또는 세금계산서로 증빙한다.

## 제3장 복리후생비

### 제8조(회식비)

부서 회식비는 1인당 월 50,000원 이내에서 집행한다.
"""

PLAIN = """법인카드 사용 규정 (가상)

제1장 총칙

제1조(목적) 이 규정은 법인카드의 사용 기준을 정한다.

제2장 사용 제한

제5조(사용 제한 시간) 법인카드는 주말과 공휴일에 사용할 수 없다.
유흥주점에는 사용할 수 없다.

제6조(분할 결제 금지) 하나의 거래를 나누어 결제해서는 안 된다.
제5조에 따라 제한된 시간에 사용한 경우에도 같다.
"""


class FakeEmbedder:
    model = "fake-e5"
    dims = 4

    def __init__(self):
        self.calls = []

    def embed(self, texts, kind):
        self.calls.append((list(texts), kind))
        return [[float(len(t)), 0.0, 0.0, 1.0] for t in texts]


# ------------------------------------------------------------------ 청킹


def test_마크다운_제목으로_섹션_경로를_만들고_섹션을_넘지_않는다():
    chunks = chunk_pages([Page(None, MARKDOWN)])
    assert [c.section for c in chunks] == [
        None,  # 문서 제목(# 하나)은 경로에서 뺀다
        "제2장 접대비 > 제5조(접대비 한도)",
        "제2장 접대비 > 제6조(접대비 증빙)",
        "제3장 복리후생비 > 제8조(회식비)",
    ]
    assert chunks[0].content == "이 문서는 데모용 가상 규정이다."
    # 같은 조의 문단은 한 청크로 모인다
    assert chunks[1].content.startswith("접대비는 거래처 1회당 300,000원")
    assert chunks[1].content.endswith("100,000원을 넘을 수 없다.")
    assert chunks[3].embedding_text() == "제3장 복리후생비 > 제8조(회식비)\n부서 회식비는 1인당 월 50,000원 이내에서 집행한다."


def test_평문_규정의_제N장_제N조를_읽고_조문_인용은_제목으로_보지_않는다():
    chunks = chunk_pages([Page(None, PLAIN)])
    sections = [c.section for c in chunks]
    assert sections == [None, "제1장 총칙 > 제1조(목적)", "제2장 사용 제한 > 제5조(사용 제한 시간)",
                        "제2장 사용 제한 > 제6조(분할 결제 금지)"]
    assert chunks[1].content == "이 규정은 법인카드의 사용 기준을 정한다."  # 제목과 같은 줄의 본문
    assert chunks[2].content == "법인카드는 주말과 공휴일에 사용할 수 없다.\n유흥주점에는 사용할 수 없다."
    assert "제5조에 따라 제한된 시간에 사용한 경우에도 같다." in chunks[3].content


def test_긴_문단은_문장_경계에서_자른다():
    sentence = "적격 증빙을 받지 못한 지출은 지출결의서에 사유를 적고 재무팀 확인을 받는다. "
    chunks = chunk_pages([Page(None, "## 제12조(증빙)\n\n" + sentence * 30)])
    assert len(chunks) > 1
    assert all(len(c.content) <= MAX_PARAGRAPH_CHARS for c in chunks)
    assert all(c.section == "제12조(증빙)" for c in chunks)
    assert all(c.content.endswith("받는다.") for c in chunks)


def test_청크의_페이지는_첫_문단의_페이지():
    # 같은 조가 다음 쪽으로 이어지면 한 청크로 모으고, 페이지는 시작한 쪽으로 둔다
    chunks = chunk_pages([Page(1, "제1조(목적) 첫 쪽 본문이다."), Page(2, "둘째 쪽 본문이다."),
                          Page(3, "제2조(정의) 셋째 쪽이다.")])
    assert [(c.page, c.section, c.content) for c in chunks] == [
        (1, "제1조(목적)", "첫 쪽 본문이다.\n둘째 쪽 본문이다."),
        (3, "제2조(정의)", "셋째 쪽이다."),
    ]


# ------------------------------------------------------------------ 텍스트 추출


def test_텍스트_인코딩():
    assert decode_text("제1조 목적".encode("utf-8")) == "제1조 목적"
    assert decode_text("﻿제1조".encode("utf-8")) == "제1조"
    assert decode_text("제1조 목적".encode("cp949")) == "제1조 목적"


def _docx(paragraphs):
    w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    body = ""
    for style, text in paragraphs:
        ppr = f'<w:pPr><w:pStyle w:val="{style}"/></w:pPr>' if style else ""
        body += f"<w:p>{ppr}<w:r><w:t>{text}</w:t></w:r></w:p>"
    xml = f'<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="{w}"><w:body>{body}</w:body></w:document>'
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("[Content_Types].xml", "<Types/>")
        z.writestr("word/document.xml", xml)
    return buf.getvalue()


def test_DOCX는_제목_스타일을_마크다운_제목으로():
    content = _docx([("Heading1", "경비 규정"), ("Heading2", "제2장 접대비"), ("Heading3", "제5조(접대비 한도)"),
                     (None, "접대비는 1회 300,000원 한도이다."), (None, "")])
    assert docx_text(content) == "# 경비 규정\n\n## 제2장 접대비\n\n### 제5조(접대비 한도)\n\n접대비는 1회 300,000원 한도이다."
    chunks = chunk_pages(extract_pages(content, "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
    assert [(c.section, c.content) for c in chunks] == [("제2장 접대비 > 제5조(접대비 한도)", "접대비는 1회 300,000원 한도이다.")]
    with pytest.raises(UnsupportedDocument):
        docx_text(b"PK\x03\x04 not a zip")


def _pdf(pages):
    """텍스트 레이어가 있는 최소 PDF (Helvetica, ASCII). xref는 pdfium이 다시 만든다."""
    objs = ["<< /Type /Catalog /Pages 2 0 R >>", None, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
    kids = []
    for text in pages:
        stream = f"BT /F1 12 Tf 72 720 Td ({text}) Tj ET"
        objs.append(f"<< /Length {len(stream)} >>\nstream\n{stream}\nendstream")
        content_no = len(objs)
        objs.append(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents {content_no} 0 R "
                    f"/Resources << /Font << /F1 3 0 R >> >> >>")
        kids.append(f"{len(objs)} 0 R")
    objs[1] = f"<< /Type /Pages /Kids [{' '.join(kids)}] /Count {len(kids)} >>"
    out = "%PDF-1.4\n" + "".join(f"{i + 1} 0 obj\n{o}\nendobj\n" for i, o in enumerate(objs))
    return (out + "trailer\n<< /Root 1 0 R >>\n%%EOF").encode("latin-1")


def test_PDF_텍스트_레이어와_페이지():
    pages = pdf_pages(_pdf(["Article 1. Purpose of this rule.", "Article 5. Limit is 300,000 won."]))
    assert [p.number for p in pages] == [1, 2]
    assert "Article 5. Limit is 300,000 won." in pages[1].text
    with pytest.raises(UnsupportedDocument):
        pdf_pages(b"%PDF-broken")


def test_지원하지_않는_형식():
    with pytest.raises(UnsupportedDocument):
        extract_pages(b"\x89PNG", "image/png")


# ------------------------------------------------------------------ 색인 worker


def test_색인은_청크마다_passage_임베딩을_만든다():
    embedder = FakeEmbedder()
    result = index_document(MARKDOWN.encode("utf-8"), "text/markdown", embedder)
    assert result["model"] == "fake-e5" and result["dims"] == 4
    assert result["charCount"] == len(MARKDOWN)
    assert [c["index"] for c in result["chunks"]] == [0, 1, 2, 3]
    assert result["chunks"][1]["section"] == "제2장 접대비 > 제5조(접대비 한도)"
    texts, kind = embedder.calls[0]
    assert kind == "passage"
    assert texts[1].startswith("제2장 접대비 > 제5조(접대비 한도)\n접대비는")  # 섹션 경로도 임베딩한다
    assert result["chunks"][1]["embedding"] == [float(len(texts[1])), 0.0, 0.0, 1.0]


def test_worker_메시지와_파일_경로_검사():
    job = {"documentId": 5, "companyId": 1, "attempt": 2, "contentType": "text/markdown",
           "fileKey": "companies/1/rules/0f8fad5b-d9cb-469f-a165-70867728950e.md"}
    fetched = []

    def fetch(key):
        fetched.append(key)
        return MARKDOWN.encode("utf-8")

    doc_id, payload = handle_job(json.dumps(job), FakeEmbedder(), fetch)
    assert doc_id == 5 and payload["attempt"] == 2 and payload["success"] is True
    assert len(payload["chunks"]) == 4
    assert fetched == [job["fileKey"]]

    _, bad = handle_job(json.dumps({**job, "fileKey": "../../etc/passwd"}), FakeEmbedder(), fetch)
    assert bad["success"] is False and "허용되지 않은 파일 경로" in bad["error"]
    _, broken = handle_job(json.dumps({**job, "contentType": "image/png"}), FakeEmbedder(), fetch)
    assert broken["success"] is False and "UnsupportedDocument" in broken["error"]
    assert handle_job(b"not json", FakeEmbedder(), fetch) is None


# ------------------------------------------------------------------ 규정 답변

# Spring RagText.facts와 같은 모양 (가상)
FACTS = {
    "질문": "접대비 한도는 얼마인가요?",
    "규정 발췌": [
        {"번호": 1, "출처": "경비 처리 규정 › 제2장 접대비 > 제5조(접대비 한도)",
         "본문": "접대비는 거래처 1회당 300,000원을 한도로 집행한다. 한도를 넘는 접대는 부서장의 사전 승인을 받아야 한다."},
        {"번호": 2, "출처": "법인카드 사용 규정 › 제3장 정산 > 제9조(위반 시 조치)",
         "본문": "이 규정을 위반하여 사용한 금액은 사용자에게 청구한다."},
    ],
}


def _answer(text, checkpoints=()):
    return FakeProvider(json.dumps({"explanation": text, "checkpoints": list(checkpoints)}, ensure_ascii=False))


def test_인용이_있는_답변은_통과한다():
    result = explain("RULE_ANSWER", FACTS, _answer("접대비는 거래처 1회당 300,000원이 한도이며, 넘으면 부서장 사전 승인이 필요합니다 [1].",
                                                   ["사전 승인 여부 확인"]))
    assert result["status"] == "GENERATED", result["meta"]
    assert result["meta"]["promptVersion"] == "rag-v3"
    # 규정 원문에 있는 "위반"은 허용한다
    assert validate("규정을 위반한 사용 금액은 사용자에게 청구합니다 [2].", [], FACTS, rag=True) == []
    # 찾지 못했다는 답은 인용 없이도 통과
    assert validate(NOT_FOUND_ANSWER, [], FACTS, rag=True) == []


@pytest.mark.parametrize(
    "text, code",
    [
        ("접대비는 거래처 1회당 300,000원이 한도입니다.", "NO_CITATION"),
        ("접대비는 거래처 1회당 300,000원이 한도입니다 [3].", "INVALID_CITATION"),
        ("접대비는 1회 500,000원까지 쓸 수 있습니다 [1].", "UNSUPPORTED_NUMBER"),
        ("접대비 한도를 넘으면 횡령에 해당합니다 [1].", "VERDICT_LANGUAGE"),
    ],
)
def test_인용_숫자_단정_검증(text, code):
    assert code in [i["code"] for i in validate(text, [], FACTS, rag=True)]


def test_질문의_금액을_규정_구간에_대입한_답은_거부한다():
    # 실제 출력: 300만원(질문)을 전결 기준에 대입해 '팀장 승인'으로 잘못 답했다
    facts = {"질문": "300만원짜리 지출은 누가 승인해?", "규정 발췌": [
        {"번호": 1, "출처": "경비 처리 규정 › 제13조(전결 기준)",
         "본문": "1건 1,000,000원 미만은 팀장, 1,000,000원 이상 5,000,000원 미만은 부서장, 5,000,000원 이상은 대표이사가 승인한다."}]}
    wrong = "300만원짜리 지출은 팀장의 승인을 받아야 합니다 [1]."
    assert "UNSUPPORTED_NUMBER" in [i["code"] for i in validate(wrong, [], facts, rag=True)]
    quoted = "전결 기준은 1,000,000원 미만 팀장, 1,000,000원 이상 5,000,000원 미만 부서장, 5,000,000원 이상 대표이사입니다 [1]."
    assert validate(quoted, [], facts, rag=True) == []
    # 다른 설명(Copilot 등)은 질문의 숫자도 근거로 본다
    assert "UNSUPPORTED_NUMBER" not in [i["code"] for i in validate(wrong, [], facts)]


def test_본문에_인용이_없으면_citations_필드의_번호를_붙인다():
    # 실제 출력: 7B 모델이 맞는 답을 하고도 본문에 [번호]를 빠뜨렸다
    content = json.dumps({"explanation": "접대비 한도는 거래처 1회당 300,000원입니다.", "citations": [1, 1, 9],
                          "checkpoints": []}, ensure_ascii=False)
    provider = FakeProvider(content)
    result = explain("RULE_ANSWER", FACTS, provider)
    assert result["status"] == "FALLBACK"  # 9번은 없는 발췌 → 거부
    assert result["meta"]["rejectedText"] == "접대비 한도는 거래처 1회당 300,000원입니다. [1][9]"
    assert provider.calls[0][2]["required"] == ["explanation", "citations", "checkpoints"]

    ok = json.dumps({"explanation": "접대비 한도는 거래처 1회당 300,000원입니다.", "citations": [1], "checkpoints": []},
                    ensure_ascii=False)
    assert explain("RULE_ANSWER", FACTS, FakeProvider(ok))["text"] == "접대비 한도는 거래처 1회당 300,000원입니다. [1]"
    # 본문에 이미 인용이 있거나 "찾지 못했습니다"면 그대로 둔다
    inline = json.dumps({"explanation": "한도는 300,000원입니다 [1].", "citations": [2], "checkpoints": []}, ensure_ascii=False)
    assert explain("RULE_ANSWER", FACTS, FakeProvider(inline))["text"] == "한도는 300,000원입니다 [1]."
    none = json.dumps({"explanation": NOT_FOUND_ANSWER, "citations": [1], "checkpoints": []}, ensure_ascii=False)
    assert explain("RULE_ANSWER", FACTS, FakeProvider(none))["text"] == NOT_FOUND_ANSWER
    # 실제 출력: 제 표현으로 '없음'을 답하고 필수 citations에 [1]을 넣었다 → 표준 문장으로, 인용 없이
    own_words = json.dumps({"explanation": "주차장 운영 시간에 대한 규정을 찾을 수 없습니다.", "citations": [1],
                            "checkpoints": []}, ensure_ascii=False)
    result = explain("RULE_ANSWER", FACTS, FakeProvider(own_words))
    assert result["status"] == "GENERATED" and result["text"] == NOT_FOUND_ANSWER
    inline_none = json.dumps({"explanation": "주차장 운영 시간에 대한 규정을 찾을 수 없습니다. 위 규정에서 언급되어 있지 않습니다 [1].",
                              "citations": [1], "checkpoints": []}, ensure_ascii=False)
    assert explain("RULE_ANSWER", FACTS, FakeProvider(inline_none))["text"] == NOT_FOUND_ANSWER
    # 첫 문장이 답이면 뒤의 '찾을 수 없다'는 그대로 둔다
    partial = json.dumps({"explanation": "접대비 한도는 거래처 1회당 300,000원입니다 [1]. 예외 규정은 찾을 수 없습니다.",
                          "citations": [1], "checkpoints": []}, ensure_ascii=False)
    assert explain("RULE_ANSWER", FACTS, FakeProvider(partial))["text"].startswith("접대비 한도는")


def test_규정_답변_템플릿은_발췌를_그대로_보여준다():
    text, checkpoints = fallback("RULE_ANSWER", FACTS)
    lines = text.split("\n")
    assert lines[0] == "관련 규정 발췌입니다. 원문을 직접 확인하세요."
    assert lines[1].startswith("[1] 경비 처리 규정 › 제2장 접대비 > 제5조(접대비 한도): 접대비는 거래처 1회당 300,000원")
    assert checkpoints == []
    assert fallback("RULE_ANSWER", {"규정 발췌": []}) == (NOT_FOUND_ANSWER, [])
    result = explain("RULE_ANSWER", FACTS, FakeProvider(error=LlmError("down")))
    assert result["status"] == "FALLBACK" and result["text"] == text


def test_청크_데이터클래스():
    assert Chunk(None, None, "본문").embedding_text() == "본문"
