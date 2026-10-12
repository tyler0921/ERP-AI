"""문장 임베딩. 구현은 sentence-transformers, 설명·테스트는 Embedder 인터페이스에만 의존한다.

기본 모델 intfloat/multilingual-e5-small(384차원, 한국어 지원, MIT). e5 규칙대로 문서는 'passage: ',
질문은 'query: ' 접두어를 붙이고, 코사인 유사도를 쓰도록 정규화한다. 모델은 첫 사용 때 내려받아 HF_HOME(볼륨)에 캐시한다.
"""

from __future__ import annotations

import logging
import threading
from typing import Protocol

log = logging.getLogger("ledgerguard.rag.embedder")

KINDS = ("query", "passage")


class Embedder(Protocol):
    model: str
    dims: int

    def embed(self, texts: list[str], kind: str) -> list[list[float]]:
        ...


class EmbeddingError(RuntimeError):
    """모델 로딩·추론 실패"""


class SentenceTransformerEmbedder:
    """CPU 추론. torch·sentence-transformers는 처음 쓸 때 import한다 (테스트 환경에는 설치하지 않는다)."""

    def __init__(self, model: str, dims: int, batch_size: int = 32) -> None:
        self.model = model
        self.dims = dims
        self.batch_size = batch_size
        self._model = None
        self._lock = threading.Lock()

    def warm_up(self) -> None:
        self._load()

    def _load(self):
        with self._lock:
            if self._model is None:
                try:
                    from sentence_transformers import SentenceTransformer

                    log.info("임베딩 모델 로딩: %s (첫 실행이면 내려받습니다)", self.model)
                    model = SentenceTransformer(self.model, device="cpu")
                except Exception as e:  # noqa: BLE001 — 네트워크·디스크 등 원인을 그대로 알린다
                    raise EmbeddingError(f"임베딩 모델을 불러오지 못했습니다: {type(e).__name__}: {e}") from e
                actual = model.get_sentence_embedding_dimension()
                if actual != self.dims:
                    raise EmbeddingError(f"임베딩 차원이 설정({self.dims})과 다릅니다: {actual}")
                self._model = model
            return self._model

    def embed(self, texts: list[str], kind: str) -> list[list[float]]:
        if kind not in KINDS:
            raise ValueError(f"kind는 {KINDS} 중 하나여야 합니다.")
        model = self._load()
        prefixed = [f"{kind}: {t}" for t in texts]
        try:
            with self._lock:  # CPU 추론을 직렬화해 worker와 /v1/embed가 서로 메모리를 다투지 않게 한다
                vectors = model.encode(prefixed, batch_size=self.batch_size, normalize_embeddings=True,
                                       convert_to_numpy=True, show_progress_bar=False)
        except Exception as e:  # noqa: BLE001
            raise EmbeddingError(f"임베딩 계산 실패: {type(e).__name__}: {e}") from e
        return [[round(float(x), 6) for x in v] for v in vectors]
