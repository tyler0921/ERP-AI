"""이상탐지 특성 → 모델 입력 행렬.

특성 자체(거래처 빈도·평균·간격 등)는 Spring이 계산해 보낸다. 여기서는 모델에 맞게 변환만 한다.
금액은 문자열("1250000.00")로 받는다. ML 입력일 뿐 회계 계산에는 쓰지 않으므로 float으로 바꿔도 된다.
"""

from __future__ import annotations

import math
from collections import Counter
from typing import Any

import numpy as np

# spec의 특성 이름. 행렬의 열 순서이기도 하다.
FEATURES = (
    "amount",
    "transactionHour",
    "weekday",
    "merchantFrequency",
    "averageAmount",
    "amountRatioToAverage",
    "monthlyTransactionCount",
    "accountCategory",
    "daysSinceLastTransaction",
)

# 이전 거래가 없을 때 쓰는 간격(일)
NO_HISTORY_DAYS = 365.0


def _num(value: Any) -> float | None:
    if value is None or value == "":
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def raw_value(features: dict[str, Any], name: str) -> Any:
    """설명에 보여줄 원래 값 (변환 전)."""
    value = features.get(name)
    if name == "accountCategory":
        return value
    return _num(value)


def to_matrix(rows: list[dict[str, Any]]) -> np.ndarray:
    """특성 dict 목록 → (n, len(FEATURES)) 행렬.

    - 금액·빈도·간격은 log1p로 치우침을 줄인다.
    - 없는 값: 시각 -1, 평균 금액 → 자기 금액, 평균 대비 비율 → 1, 직전 거래 간격 → 365일.
    - 계정과목은 모집단 내 빈도(0~1)로 바꾼다. 드문 계정과목일수록 작다.
    """
    categories = Counter(str(r.get("accountCategory")) for r in rows)
    n = max(len(rows), 1)
    matrix = []
    for r in rows:
        amount = max(_num(r.get("amount")) or 0.0, 0.0)
        average = _num(r.get("averageAmount"))
        average = amount if average is None else max(average, 0.0)
        ratio = _num(r.get("amountRatioToAverage"))
        ratio = 1.0 if ratio is None or ratio <= 0 else ratio
        hour = _num(r.get("transactionHour"))
        days = _num(r.get("daysSinceLastTransaction"))
        days = NO_HISTORY_DAYS if days is None else min(max(days, 0.0), 3650.0)
        matrix.append(
            [
                math.log1p(amount),
                -1.0 if hour is None else hour,
                _num(r.get("weekday")) or 0.0,
                math.log1p(max(_num(r.get("merchantFrequency")) or 0.0, 0.0)),
                math.log1p(average),
                math.log(ratio),
                math.log1p(max(_num(r.get("monthlyTransactionCount")) or 0.0, 0.0)),
                categories[str(r.get("accountCategory"))] / n,
                math.log1p(days),
            ]
        )
    return np.asarray(matrix, dtype=float).reshape(len(rows), len(FEATURES))
