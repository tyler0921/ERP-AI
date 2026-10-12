"""회계 규정 색인 작업 큐 소비자.

Spring이 LPUSH한 작업(ledgerguard:rag:jobs)을 꺼내 SeaweedFS에서 원본을 읽고, 텍스트 추출 → 청킹 → 임베딩 후
Spring 내부 API(/internal/v1/rules/documents/{id}/index)로 청크와 벡터를 보낸다. DB에는 접근하지 않는다.
"""

from __future__ import annotations

import json
import logging
import re
import threading
import time

import httpx
import redis

from app.config import settings
from app.rag.embedder import Embedder
from app.rag.text import chunk_pages, extract_pages

log = logging.getLogger("ledgerguard.rag.worker")

# Spring이 만드는 저장 경로 형식만 허용한다 (경로 조작·SSRF 방지)
FILE_KEY_RE = re.compile(r"^companies/\d+/rules/[0-9a-f\-]{36}\.(pdf|docx|md|txt)$")
MAX_CHUNKS = 2000


def index_document(content: bytes, content_type: str, embedder: Embedder) -> dict:
    """원본 → 콜백 본문의 성공 부분 {model, dims, charCount, chunks}."""
    pages = extract_pages(content, content_type)
    chunks = chunk_pages(pages)[:MAX_CHUNKS]
    vectors = embedder.embed([c.embedding_text() for c in chunks], "passage") if chunks else []
    return {
        "model": embedder.model,
        "dims": embedder.dims,
        "charCount": sum(len(p.text) for p in pages),
        "chunks": [
            {"index": i, "section": c.section, "page": c.page, "content": c.content, "embedding": v}
            for i, (c, v) in enumerate(zip(chunks, vectors))
        ],
    }


def handle_job(raw: bytes | str, embedder: Embedder, fetch) -> tuple[int, dict] | None:
    """작업 메시지 → (문서 ID, 콜백 본문). fetch(file_key) → bytes. 잘못된 메시지는 None."""
    try:
        job = json.loads(raw)
        document_id = int(job["documentId"])
        attempt = int(job["attempt"])
        file_key = str(job["fileKey"])
        content_type = str(job["contentType"])
    except (ValueError, KeyError, TypeError):
        log.error("잘못된 색인 작업 메시지를 버립니다: %r", raw[:200])
        return None
    try:
        if not FILE_KEY_RE.match(file_key):
            raise ValueError("허용되지 않은 파일 경로")
        content = fetch(file_key)
        if len(content) > settings.max_file_bytes:
            raise ValueError("파일이 너무 큽니다.")
        payload = {"attempt": attempt, "success": True, **index_document(content, content_type, embedder)}
    except Exception as e:  # noqa: BLE001 — 어떤 실패든 Spring에 알려 다시 색인할 수 있게 한다
        log.exception("규정 문서 %s 색인 실패", document_id)
        payload = {"attempt": attempt, "success": False, "error": f"{type(e).__name__}: {e}"[:500]}
    return document_id, payload


def fetch_from_filer(file_key: str) -> bytes:
    url = f"{settings.filer_url}/{settings.filer_root}/{file_key}"
    with httpx.Client(timeout=30) as http:
        res = http.get(url)
        res.raise_for_status()
        return res.content


class RagWorker:
    def __init__(self, embedder: Embedder) -> None:
        self.embedder = embedder
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        self._thread = threading.Thread(target=self._run, name="rag-worker", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()

    def _run(self) -> None:
        warm_up = getattr(self.embedder, "warm_up", None)
        if warm_up is not None:
            try:
                warm_up()  # 첫 질문·색인이 모델 다운로드를 기다리지 않게 미리 불러 둔다
            except Exception:  # noqa: BLE001 — 실제 작업 때 다시 시도한다
                log.exception("임베딩 모델 사전 로드 실패")
        client = redis.Redis.from_url(settings.valkey_url, socket_timeout=10)
        log.info("RAG worker started (queue=%s, model=%s)", settings.rag_queue_key, self.embedder.model)
        while not self._stop.is_set():
            try:
                item = client.brpop([settings.rag_queue_key], timeout=5)
            except redis.RedisError as e:
                log.warning("Valkey 연결 실패, 5초 후 재시도: %s", e)
                time.sleep(5)
                continue
            if item is None:
                continue
            handled = handle_job(item[1], self.embedder, fetch_from_filer)
            if handled is not None:
                self._callback(*handled)

    def _callback(self, document_id: int, payload: dict) -> None:
        url = f"{settings.backend_url}/internal/v1/rules/documents/{document_id}/index"
        headers = {"X-Internal-Token": settings.internal_token}
        for attempt in range(3):
            try:
                with httpx.Client(timeout=60) as http:
                    res = http.post(url, json=payload, headers=headers)
                if res.status_code < 500:
                    if res.status_code >= 400:
                        log.warning("콜백 거부 (규정 문서 %s): %s %s", document_id, res.status_code, res.text[:200])
                    return
            except httpx.HTTPError as e:
                log.warning("콜백 실패 (규정 문서 %s, %d회): %s", document_id, attempt + 1, e)
            time.sleep(2 ** attempt)
        log.error("콜백을 포기합니다 (규정 문서 %s). Spring의 시간 초과 처리로 넘어갑니다.", document_id)
