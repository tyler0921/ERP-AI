"""LedgerGuard AI 서비스 (FastAPI).

- OCR·필드 추출·계정과목 후보, 이상탐지 ML 점수를 담당한다. 판단(검증·승인·Risk Level)은 하지 않는다.
- OCR 운영 경로는 Valkey 큐 worker이고, /v1/extract는 디버깅·데모용 동기 API다(저장하지 않음).
- /v1/anomaly/score는 Spring이 동기 호출한다. 요청마다 모집단으로 IsolationForest를 학습하고 저장하지 않는다.
- LLM 설명(Phase 5·6)과 Copilot 조회 계획(Phase 7)은 별도 Valkey 큐(ledgerguard:llm:jobs) worker가 Ollama로 만들고
  Spring에 콜백한다. /v1/explain, /v1/copilot/plan은 디버그·데모용.
- 회계 규정 RAG(Phase 8): 색인은 Valkey 큐(ledgerguard:rag:jobs) worker가 텍스트 추출·청킹·임베딩 후 Spring에 콜백하고,
  질문 임베딩은 Spring이 /v1/embed를 동기 호출한다. 벡터 저장·검색(pgvector)은 Spring이 한다.
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.concurrency import run_in_threadpool
from pydantic import BaseModel, Field

from app.anomaly.service import score_samples
from app.config import settings
from app.copilot.planner import make_plan
from app.llm.explain import TARGET_TYPES, explain
from app.llm.provider import LlmError, OllamaProvider
from app.ocr.engine import PaddleOcrEngine
from app.ocr.images import UnsupportedFile
from app.pipeline import DOC_TYPES, process
from app.rag.embedder import KINDS, EmbeddingError, SentenceTransformerEmbedder

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

engine = PaddleOcrEngine()
llm = OllamaProvider(settings.ollama_url, settings.llm_model, settings.llm_timeout_s)
embedder = SentenceTransformerEmbedder(settings.embedding_model, settings.embedding_dims)


@asynccontextmanager
async def lifespan(app: FastAPI):
    workers = []
    if settings.worker_enabled:
        from app.worker import Worker

        workers.append(Worker(engine))
    if settings.llm_worker_enabled:
        from app.llm.worker import LlmWorker

        workers.append(LlmWorker(llm))
    if settings.rag_worker_enabled:
        from app.rag.worker import RagWorker

        workers.append(RagWorker(embedder))
    for w in workers:
        w.start()
    yield
    for w in workers:
        w.stop()


app = FastAPI(title="LedgerGuard AI Service", version="0.5.0", lifespan=lifespan)


@app.get("/health")
def health() -> dict:
    try:
        llm_status = "READY" if llm.has_model() else "MODEL_MISSING"
    except LlmError:
        llm_status = "UNREACHABLE"
    return {
        "status": "UP",
        "ocrEngine": engine.name,
        "ocrModel": engine.model,
        "worker": settings.worker_enabled,
        "llm": {"provider": llm.name, "model": llm.model, "status": llm_status, "worker": settings.llm_worker_enabled},
    }


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


class AnomalySample(BaseModel):
    id: str = Field(min_length=1, max_length=64)
    features: dict[str, Any]


class AnomalyScoreRequest(BaseModel):
    samples: list[AnomalySample] = Field(max_length=50_000)
    min_samples: int = Field(30, alias="minSamples", ge=2, le=10_000)


@app.post("/v1/anomaly/score")
async def anomaly_score(request: AnomalyScoreRequest) -> dict:
    samples = [s.model_dump() for s in request.samples]
    # 학습은 CPU를 쓰므로 이벤트 루프를 막지 않게 스레드에서 실행한다.
    return await run_in_threadpool(score_samples, samples, request.min_samples)


class ExplainRequest(BaseModel):
    target_type: str = Field("ANOMALY_RESULT", alias="targetType")
    facts: dict[str, Any]


@app.post("/v1/explain")
async def explain_once(request: ExplainRequest) -> dict:
    """근거 데이터 → 설명 (디버그·데모용, 저장하지 않음). 운영 경로는 LLM 큐 worker다."""
    if request.target_type not in TARGET_TYPES:
        raise HTTPException(400, f"targetType은 {TARGET_TYPES} 중 하나여야 합니다.")
    return await run_in_threadpool(explain, request.target_type, request.facts, llm)


class PlanRequest(BaseModel):
    question: str = Field(min_length=1, max_length=500)
    today: str
    accounts: list[dict[str, Any]] = []


@app.post("/v1/copilot/plan")
async def plan_once(request: PlanRequest) -> dict:
    """질문 → 조회 계획 (디버그·데모용, 저장·실행하지 않음). 운영 경로는 LLM 큐 worker다."""
    return await run_in_threadpool(make_plan, request.model_dump(), llm)


class EmbedRequest(BaseModel):
    texts: list[str] = Field(min_length=1, max_length=64)
    kind: str = "query"


@app.post("/v1/embed")
async def embed(request: EmbedRequest) -> dict:
    """문장 임베딩 (backend가 규정 질문 검색 때 동기 호출). 저장하지 않는다."""
    if request.kind not in KINDS:
        raise HTTPException(400, f"kind는 {KINDS} 중 하나여야 합니다.")
    if any(len(t) > 2000 for t in request.texts):
        raise HTTPException(400, "문장은 2000자까지 임베딩할 수 있습니다.")
    try:
        vectors = await run_in_threadpool(embedder.embed, request.texts, request.kind)
    except EmbeddingError as e:
        raise HTTPException(503, str(e)) from e
    return {"model": embedder.model, "dims": embedder.dims, "vectors": vectors}
