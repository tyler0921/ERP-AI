"""이상탐지 모델 인터페이스와 IsolationForest 구현.

모델은 0~1 이상 점수만 낸다. 이상 여부의 판단(Risk Score·Level)과 최종 판정은 Spring과 사람이 한다.
다른 모델(LOF, One-Class SVM 등)과 비교하려면 AnomalyModel을 구현해 바꾸면 된다.
"""

from __future__ import annotations

from typing import Protocol

import numpy as np
import sklearn
from sklearn.ensemble import IsolationForest


class AnomalyModel(Protocol):
    name: str

    def score(self, matrix: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        """모집단으로 학습하고 각 행의 (정규화 점수 0~1, 원점수)를 돌려준다."""
        ...


class IsolationForestModel:
    """비지도 학습이라 저장된 모델이 없다. 요청마다 해당 회사·원천의 모집단으로 학습한다.

    원점수는 논문식 이상 점수 s = -score_samples(x) (0~1, 0.5 근처가 보통, 1에 가까울수록 이상)이고,
    정규화 점수는 clamp((s - 0.5) / 0.25, 0, 1)이다. random_state를 고정해 같은 데이터면 같은 점수가 나온다.
    """

    n_estimators = 200
    random_state = 42

    @property
    def name(self) -> str:
        return f"isolation_forest(scikit-learn {sklearn.__version__}, n_estimators={self.n_estimators})"

    def score(self, matrix: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        forest = IsolationForest(
            n_estimators=self.n_estimators,
            contamination="auto",
            random_state=self.random_state,
        )
        forest.fit(matrix)
        raw = -forest.score_samples(matrix)
        normalized = np.clip((raw - 0.5) / 0.25, 0.0, 1.0)
        return normalized, raw
