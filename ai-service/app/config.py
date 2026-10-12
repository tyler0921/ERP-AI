"""환경 변수 기반 설정. 기본값은 docker compose 내부 네트워크 기준."""

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    valkey_url: str = os.getenv("VALKEY_URL", "redis://localhost:6379/0")
    queue_key: str = os.getenv("OCR_QUEUE_KEY", "ledgerguard:ocr:jobs")
    filer_url: str = os.getenv("SEAWEEDFS_FILER_URL", "http://localhost:8888").rstrip("/")
    filer_root: str = os.getenv("SEAWEEDFS_ROOT", "ledgerguard").strip("/")
    backend_url: str = os.getenv("BACKEND_URL", "http://localhost:8080").rstrip("/")
    internal_token: str = os.getenv("INTERNAL_API_TOKEN", "dev-internal-token-change-me")
    worker_enabled: bool = os.getenv("WORKER_ENABLED", "true").lower() == "true"
    max_file_bytes: int = int(os.getenv("MAX_FILE_BYTES", str(10 * 1024 * 1024)))
    max_pdf_pages: int = int(os.getenv("MAX_PDF_PAGES", "3"))
    # Phase 5 LLM 설명 (Ollama)
    ollama_url: str = os.getenv("OLLAMA_URL", "http://localhost:11434").rstrip("/")
    llm_model: str = os.getenv("LLM_MODEL", "qwen2.5:7b-instruct")
    llm_queue_key: str = os.getenv("LLM_QUEUE_KEY", "ledgerguard:llm:jobs")
    llm_worker_enabled: bool = os.getenv("LLM_WORKER_ENABLED", "true").lower() == "true"
    llm_timeout_s: float = float(os.getenv("LLM_TIMEOUT_S", "120"))
    # 모델이 없으면 worker 시작 때 Ollama에 내려받기를 요청한다 (첫 실행 수 분 소요)
    llm_pull_on_start: bool = os.getenv("LLM_PULL_ON_START", "true").lower() == "true"
    # Phase 8 회계 규정 RAG (sentence-transformers). 차원은 backend app.rag.embedding-dims와 같아야 한다
    embedding_model: str = os.getenv("EMBEDDING_MODEL", "intfloat/multilingual-e5-small")
    embedding_dims: int = int(os.getenv("EMBEDDING_DIMS", "384"))
    rag_queue_key: str = os.getenv("RAG_QUEUE_KEY", "ledgerguard:rag:jobs")
    rag_worker_enabled: bool = os.getenv("RAG_WORKER_ENABLED", "true").lower() == "true"


settings = Settings()
