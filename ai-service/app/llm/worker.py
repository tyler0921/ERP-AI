"""LLM 설명 작업 큐 소비자.

OCR 큐와 별도 스레드로 돌아 서로를 막지 않는다. Spring이 LPUSH한 작업(근거 데이터 포함)을 BRPOP으로 꺼내
설명을 만들고 Spring 내부 API로 콜백한다. 콜백이 끝내 실패하면 Spring의 시간 초과 처리에 맡긴다.
"""

from __future__ import annotations

import json
import logging
import threading
import time

import httpx
import redis

from app.config import settings
from app.copilot.planner import make_plan
from app.llm.explain import TARGET_TYPES, explain
from app.llm.provider import LlmError, LlmProvider

log = logging.getLogger("ledgerguard.llm.worker")


PLAN_TARGET = "COPILOT_PLAN"


def handle_job(raw: bytes | str, provider: LlmProvider) -> tuple[str, dict] | None:
    """작업 메시지 → (콜백 경로, 본문). 잘못된 메시지는 None.

    설명 작업은 /internal/v1/explanations/{id}/result, Copilot 계획 작업(COPILOT_PLAN)은
    /internal/v1/copilot/questions/{id}/plan으로 보낸다.
    """
    try:
        job = json.loads(raw)
        explanation_id = int(job["explanationId"])
        attempt = int(job["attempt"])
        target_type = str(job["targetType"])
        facts = job["facts"]
        if (target_type not in TARGET_TYPES and target_type != PLAN_TARGET) or not isinstance(facts, dict):
            raise ValueError(target_type)
    except (ValueError, KeyError, TypeError):
        log.error("잘못된 LLM 작업 메시지를 버립니다: %r", raw[:200])
        return None
    if target_type == PLAN_TARGET:
        path = f"/internal/v1/copilot/questions/{explanation_id}/plan"
    else:
        path = f"/internal/v1/explanations/{explanation_id}/result"
    try:
        if target_type == PLAN_TARGET:
            result = make_plan(facts, provider)
        else:
            result = explain(target_type, facts, provider)
        payload = {"attempt": attempt, "success": True, **result}
    except Exception as e:  # noqa: BLE001 — 어떤 실패든 Spring에 알려 사람이 다시 시도할 수 있게 한다
        log.exception("LLM 작업 %s(%s) 실패", explanation_id, target_type)
        payload = {"attempt": attempt, "success": False, "error": f"{type(e).__name__}: {e}"[:500]}
    return path, payload


class LlmWorker:
    def __init__(self, provider: LlmProvider) -> None:
        self.provider = provider
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        self._thread = threading.Thread(target=self._run, name="llm-worker", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()

    def _run(self) -> None:
        ensure = getattr(self.provider, "ensure_model", None)
        if settings.llm_pull_on_start and ensure is not None:
            try:
                ensure()
            except LlmError as e:  # Ollama가 없어도 worker는 돈다 — 그때는 템플릿 설명으로 대체된다
                log.warning("LLM 모델 준비 실패 (템플릿 설명으로 대체됩니다): %s", e)
        client = redis.Redis.from_url(settings.valkey_url, socket_timeout=10)
        log.info("LLM worker started (queue=%s, model=%s)", settings.llm_queue_key, self.provider.model)
        while not self._stop.is_set():
            try:
                item = client.brpop([settings.llm_queue_key], timeout=5)
            except redis.RedisError as e:
                log.warning("Valkey 연결 실패, 5초 후 재시도: %s", e)
                time.sleep(5)
                continue
            if item is None:
                continue
            handled = handle_job(item[1], self.provider)
            if handled is not None:
                self._callback(*handled)

    def _callback(self, path: str, payload: dict) -> None:
        url = f"{settings.backend_url}{path}"
        headers = {"X-Internal-Token": settings.internal_token}
        for attempt in range(3):
            try:
                with httpx.Client(timeout=15) as http:
                    res = http.post(url, json=payload, headers=headers)
                if res.status_code < 500:
                    if res.status_code >= 400:
                        log.warning("콜백 거부 (%s): %s %s", path, res.status_code, res.text[:200])
                    return
            except httpx.HTTPError as e:
                log.warning("콜백 실패 (%s, %d회): %s", path, attempt + 1, e)
            time.sleep(2 ** attempt)
        log.error("콜백을 포기합니다 (%s). Spring의 시간 초과 처리로 넘어갑니다.", path)
