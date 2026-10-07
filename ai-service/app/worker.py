"""Valkey 작업 큐 소비자.

Spring이 LPUSH한 작업을 BRPOP으로 꺼내 처리하고, 결과(성공/실패)를 Spring 내부 API로 콜백한다.
OCR은 CPU를 많이 쓰므로 한 번에 하나씩 처리한다. 콜백이 끝내 실패하면 Spring의 시간 초과 처리에 맡긴다.
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
from app.ocr.engine import OcrEngine
from app.pipeline import DOC_TYPES, process

log = logging.getLogger("ledgerguard.worker")

# Spring이 만드는 저장 경로 형식만 허용한다 (경로 조작·SSRF 방지)
FILE_KEY_RE = re.compile(r"^companies/\d+/documents/[0-9a-f\-]{36}\.(png|jpg|jpeg|pdf)$")


class Worker:
    def __init__(self, engine: OcrEngine) -> None:
        self.engine = engine
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        self._thread = threading.Thread(target=self._run, name="ocr-worker", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()

    def _run(self) -> None:
        try:
            # 첫 작업이 모델 로딩 시간까지 기다리지 않도록 미리 로드한다 (첫 실행이면 모델 다운로드 포함)
            warm_up = getattr(self.engine, "warm_up", None)
            if warm_up is not None:
                warm_up()
        except Exception:  # noqa: BLE001 — 실패해도 실제 작업 처리 때 다시 시도한다
            log.exception("OCR 모델 사전 로드 실패")
        client = redis.Redis.from_url(settings.valkey_url, socket_timeout=10)
        log.info("OCR worker started (queue=%s)", settings.queue_key)
        while not self._stop.is_set():
            try:
                item = client.brpop([settings.queue_key], timeout=5)
            except redis.RedisError as e:
                log.warning("Valkey 연결 실패, 5초 후 재시도: %s", e)
                time.sleep(5)
                continue
            if item is None:
                continue
            self.handle(item[1])

    def handle(self, raw: bytes) -> None:
        try:
            job = json.loads(raw)
            document_id = int(job["documentId"])
            attempt = int(job["attempt"])
        except (ValueError, KeyError, TypeError):
            log.error("잘못된 작업 메시지를 버립니다: %r", raw[:200])
            return

        try:
            payload = {"attempt": attempt, "success": True, "result": self._process(job)}
        except Exception as e:  # noqa: BLE001 — 어떤 실패든 Spring에 알려 사람이 처리하게 한다
            log.exception("문서 %s 처리 실패", document_id)
            payload = {"attempt": attempt, "success": False, "error": f"{type(e).__name__}: {e}"[:500]}
        self._callback(document_id, payload)

    def _process(self, job: dict) -> dict:
        file_key = str(job.get("fileKey", ""))
        doc_type = str(job.get("docType", ""))
        if not FILE_KEY_RE.match(file_key):
            raise ValueError("허용되지 않은 파일 경로")
        if doc_type not in DOC_TYPES:
            raise ValueError(f"지원하지 않는 문서 유형: {doc_type}")
        url = f"{settings.filer_url}/{settings.filer_root}/{file_key}"
        with httpx.Client(timeout=30) as http:
            res = http.get(url)
            res.raise_for_status()
            content = res.content
        if len(content) > settings.max_file_bytes:
            raise ValueError("파일이 너무 큽니다.")
        return process(content, doc_type, self.engine)

    def _callback(self, document_id: int, payload: dict) -> None:
        url = f"{settings.backend_url}/internal/v1/documents/{document_id}/ai-result"
        headers = {"X-Internal-Token": settings.internal_token}
        for attempt in range(3):
            try:
                with httpx.Client(timeout=15) as http:
                    res = http.post(url, json=payload, headers=headers)
                if res.status_code < 500:
                    if res.status_code >= 400:
                        log.warning("콜백 거부 (문서 %s): %s %s", document_id, res.status_code, res.text[:200])
                    return
            except httpx.HTTPError as e:
                log.warning("콜백 실패 (문서 %s, %d회): %s", document_id, attempt + 1, e)
            time.sleep(2 ** attempt)
        log.error("콜백을 포기합니다 (문서 %s). Spring의 시간 초과 처리로 넘어갑니다.", document_id)
