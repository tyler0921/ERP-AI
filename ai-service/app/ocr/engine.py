"""OCR 엔진 인터페이스와 PaddleOCR 구현.

추출 로직은 OcrEngine 인터페이스에만 의존하므로 다른 엔진으로 바꾸거나 테스트용 가짜 엔진을 쓸 수 있다.
"""

from __future__ import annotations

import threading
import time
from typing import Protocol

import numpy as np
from PIL import Image

from app.ocr.models import OcrResult, Segment


class OcrEngine(Protocol):
    name: str

    def recognize(self, pages: list[Image.Image]) -> OcrResult: ...


class PaddleOcrEngine:
    """PaddleOCR 3.x (CPU). 모델 로딩이 무거워 첫 호출 때 한 번만 만든다.

    - 한국어 인식 모델을 명시한다. (lang="korean"만 주면 범용 모델이 선택돼 한글을 못 읽는다)
    - Paddle 3.3 CPU의 oneDNN 버그(ConvertPirAttribute2RuntimeAttribute)를 피하려고 enable_mkldnn=False.
    - Paddle 예측기는 스레드 안전하지 않아 lock으로 직렬화한다.
    """

    name = "paddleocr"
    det_model = "PP-OCRv5_mobile_det"
    rec_model = "korean_PP-OCRv5_mobile_rec"
    min_score = 0.3

    def __init__(self) -> None:
        self._ocr = None
        self._lock = threading.Lock()

    @property
    def model(self) -> str:
        return f"{self.det_model}+{self.rec_model}"

    def _get(self):
        if self._ocr is None:
            from paddleocr import PaddleOCR  # 무거운 import는 실제 사용할 때만

            self._ocr = PaddleOCR(
                text_detection_model_name=self.det_model,
                text_recognition_model_name=self.rec_model,
                use_doc_orientation_classify=False,
                use_doc_unwarping=False,
                use_textline_orientation=False,
                enable_mkldnn=False,
            )
        return self._ocr

    def warm_up(self) -> None:
        with self._lock:
            self._get()

    def recognize(self, pages: list[Image.Image]) -> OcrResult:
        started = time.perf_counter()
        segments: list[Segment] = []
        with self._lock:
            ocr = self._get()
            for page_no, page in enumerate(pages):
                for res in ocr.predict(np.array(page.convert("RGB"))):
                    for text, score, box in zip(res["rec_texts"], res["rec_scores"], res["rec_boxes"]):
                        text = str(text).strip()
                        if not text or float(score) < self.min_score:
                            continue
                        x0, y0, x1, y1 = (float(v) for v in box)
                        segments.append(Segment(text, float(score), x0, y0, x1, y1, page_no))
        return OcrResult(
            segments=segments,
            engine=self.name,
            model=self.model,
            duration_ms=int((time.perf_counter() - started) * 1000),
            pages=len(pages),
        )
