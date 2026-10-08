"""이상탐지 점수 + 근거.

근거(topFeatures)는 모델과 무관하게, 변환된 특성마다 모집단 중앙값·MAD 기준 robust z-score를 계산해
|z| ≥ 2인 특성을 큰 순서로 최대 3개 돌려준다. ("금액이 모집단 중앙값에서 크게 벗어남")
"""

from __future__ import annotations

import time
from typing import Any

import numpy as np

from app.anomaly.features import FEATURES, raw_value, to_matrix
from app.anomaly.model import AnomalyModel, IsolationForestModel

Z_THRESHOLD = 2.0
MAX_TOP_FEATURES = 3
# MAD를 정규분포 표준편차 척도로 맞추는 상수
MAD_SCALE = 1.4826


def robust_z(matrix: np.ndarray) -> np.ndarray:
    median = np.median(matrix, axis=0)
    mad = np.median(np.abs(matrix - median), axis=0) * MAD_SCALE
    # 값의 절반 이상이 같으면 MAD가 0이 된다. 그때는 표준편차로, 그것도 0이면(상수 열) 편차 없음으로 본다.
    std = matrix.std(axis=0)
    scale = np.where(mad > 0, mad, std)
    with np.errstate(divide="ignore", invalid="ignore"):
        z = np.where(scale > 0, (matrix - median) / scale, 0.0)
    return z


def _medians(rows: list[dict[str, Any]]) -> dict[str, float | None]:
    result: dict[str, float | None] = {}
    for name in FEATURES:
        if name == "accountCategory":
            result[name] = None
            continue
        values = [v for v in (raw_value(r, name) for r in rows) if v is not None]
        result[name] = round(float(np.median(values)), 2) if values else None
    return result


def _display(value: Any) -> Any:
    return round(value, 2) if isinstance(value, float) else value


def score_samples(samples: list[dict[str, Any]], min_samples: int, model: AnomalyModel | None = None) -> dict:
    """samples: [{"id": str, "features": {...}}] → {status, model, message, scores[], durationMs}"""
    model = model or IsolationForestModel()
    started = time.perf_counter()
    if len(samples) < max(min_samples, 2):
        return {
            "status": "INSUFFICIENT_DATA",
            "model": model.name,
            "message": f"표본이 {len(samples)}건으로 최소 {min_samples}건보다 적어 ML을 건너뛰었습니다.",
            "scores": [],
            "durationMs": 0,
        }

    rows = [s["features"] for s in samples]
    matrix = to_matrix(rows)
    normalized, raw = model.score(matrix)
    z = robust_z(matrix)
    medians = _medians(rows)

    scores = []
    for i, sample in enumerate(samples):
        order = np.argsort(-np.abs(z[i]))
        top = []
        for j in order[:MAX_TOP_FEATURES]:
            if abs(z[i, j]) < Z_THRESHOLD:
                break
            name = FEATURES[j]
            top.append(
                {
                    "feature": name,
                    "value": _display(raw_value(rows[i], name)),
                    "median": medians[name],
                    "zScore": round(float(z[i, j]), 2),
                }
            )
        scores.append(
            {
                "id": sample["id"],
                "score": round(float(normalized[i]), 4),
                "rawScore": round(float(raw[i]), 4),
                "topFeatures": top,
            }
        )
    return {
        "status": "OK",
        "model": model.name,
        "message": None,
        "scores": scores,
        "durationMs": int((time.perf_counter() - started) * 1000),
    }
