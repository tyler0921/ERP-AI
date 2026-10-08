"""LLM Provider 인터페이스와 Ollama 구현.

설명 로직은 LlmProvider에만 의존하므로 다른 로컬 LLM 서버로 바꾸거나 테스트용 가짜 provider를 쓸 수 있다.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import Protocol

import httpx

log = logging.getLogger("ledgerguard.llm")


class LlmError(RuntimeError):
    """LLM 서버 연결 실패·오류 응답·시간 초과"""


@dataclass(frozen=True)
class LlmReply:
    content: str
    duration_ms: int


class LlmProvider(Protocol):
    name: str
    model: str

    def chat(self, system: str, user: str, schema: dict) -> LlmReply:
        """schema(JSON Schema)를 따르는 JSON 문자열을 돌려준다."""
        ...


class OllamaProvider:
    """Ollama /api/chat. 구조화 출력(format=JSON Schema)으로 JSON만 받는다.

    설명이 매번 크게 달라지지 않도록 temperature를 낮추고 seed를 고정한다.
    """

    name = "ollama"

    def __init__(self, base_url: str, model: str, timeout_s: float = 120.0,
                 transport: httpx.BaseTransport | None = None) -> None:
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.timeout_s = timeout_s
        self._transport = transport

    def _client(self, timeout: float) -> httpx.Client:
        return httpx.Client(base_url=self.base_url, timeout=timeout, transport=self._transport)

    def chat(self, system: str, user: str, schema: dict) -> LlmReply:
        body = {
            "model": self.model,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
            "stream": False,
            "format": schema,
            "options": {"temperature": 0.2, "seed": 42, "num_ctx": 4096},
            "keep_alive": "30m",
        }
        started = time.perf_counter()
        try:
            with self._client(self.timeout_s) as http:
                res = http.post("/api/chat", json=body)
                res.raise_for_status()
                content = res.json()["message"]["content"]
        except (httpx.HTTPError, KeyError, ValueError) as e:
            raise LlmError(f"Ollama 호출 실패: {type(e).__name__}: {e}") from e
        return LlmReply(content=content, duration_ms=int((time.perf_counter() - started) * 1000))

    def has_model(self) -> bool:
        try:
            with self._client(2) as http:
                res = http.get("/api/tags")
                res.raise_for_status()
                names = {m.get("name") for m in res.json().get("models", [])}
        except (httpx.HTTPError, ValueError) as e:
            raise LlmError(f"Ollama 연결 실패: {e}") from e
        return self.model in names or f"{self.model}:latest" in names

    def ensure_model(self) -> None:
        """모델이 없으면 내려받는다. 수 GB라 오래 걸릴 수 있다."""
        if self.has_model():
            return
        log.info("LLM 모델 %s 내려받는 중 (첫 실행)...", self.model)
        try:
            with self._client(3600) as http:
                res = http.post("/api/pull", json={"model": self.model, "stream": False})
                res.raise_for_status()
        except httpx.HTTPError as e:
            raise LlmError(f"모델 내려받기 실패: {e}") from e
        log.info("LLM 모델 %s 준비 완료", self.model)
