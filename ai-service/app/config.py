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


settings = Settings()
