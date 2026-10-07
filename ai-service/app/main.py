"""LedgerGuard AI 서비스 (FastAPI).

- OCR·필드 추출·계정과목 후보를 담당한다. 판단(검증·승인)은 하지 않는다.
- 운영 경로는 Valkey 큐 worker이고, /v1/extract는 디버깅·데모용 동기 API다(저장하지 않음).
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.concurrency import run_in_threadpool

from app.config import settings
from app.ocr.engine import PaddleOcrEngine
from app.ocr.images import UnsupportedFile
from app.pipeline import DOC_TYPES, process

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

engine = PaddleOcrEngine()


@asynccontextmanager
async def lifespan(app: FastAPI):
    worker = None
    if settings.worker_enabled:
        from app.worker import Worker

        worker = Worker(engine)
        worker.start()
    yield
    if worker is not None:
        worker.stop()


app = FastAPI(title="LedgerGuard AI Service", version="0.3.0", lifespan=lifespan)


@app.get("/health")
def health() -> dict:
    return {"status": "UP", "ocrEngine": engine.name, "ocrModel": engine.model, "worker": settings.worker_enabled}


@app.post("/v1/extract")
async def extract(file: UploadFile = File(...), doc_type: str = Form("RECEIPT", alias="docType")) -> dict:
    if doc_type not in DOC_TYPES:
        raise HTTPException(400, f"docType은 {DOC_TYPES} 중 하나여야 합니다.")
    content = await file.read(settings.max_file_bytes + 1)
    if len(content) > settings.max_file_bytes:
        raise HTTPException(413, "파일이 너무 큽니다.")
    try:
        return await run_in_threadpool(process, content, doc_type, engine)
    except UnsupportedFile as e:
        raise HTTPException(415, str(e)) from e
