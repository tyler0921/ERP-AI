"""이상탐지 ML: 특성 변환, IsolationForest 점수, robust z 근거, API."""

import random

import numpy as np
from fastapi.testclient import TestClient

from app.anomaly.features import FEATURES, to_matrix
from app.anomaly.service import robust_z, score_samples


def normal_population(n: int = 80, seed: int = 7) -> list[dict]:
    """평일 낮, 거래처별로 비슷한 금액이 반복되는 가상 지출"""
    rng = random.Random(seed)
    samples = []
    for i in range(n):
        amount = rng.choice([55000, 120000, 330000, 440000]) * rng.uniform(0.95, 1.05)
        samples.append(
            {
                "id": f"BANK_TRANSACTION:{i}",
                "features": {
                    "amount": f"{amount:.2f}",
                    "transactionHour": rng.randint(9, 17),
                    "weekday": rng.randint(1, 5),
                    "merchantFrequency": rng.randint(2, 4),
                    "averageAmount": f"{amount * rng.uniform(0.97, 1.03):.2f}",
                    "amountRatioToAverage": round(rng.uniform(0.95, 1.05), 3),
                    "monthlyTransactionCount": 1,
                    "accountCategory": rng.choice(["5400", "5800", "5300"]),
                    "daysSinceLastTransaction": rng.randint(28, 32),
                },
            }
        )
    return samples


def outlier() -> dict:
    """spec 예시: 심야, 처음 보는 계정과목, 평균의 7.6배"""
    return {
        "id": "BANK_TRANSACTION:999",
        "features": {
            "amount": "4950000.00",
            "transactionHour": 23,
            "weekday": 6,
            "merchantFrequency": 0,
            "averageAmount": "650000.00",
            "amountRatioToAverage": 7.615,
            "monthlyTransactionCount": 2,
            "accountCategory": "5900",
            "daysSinceLastTransaction": 0,
        },
    }


def by_id(result: dict) -> dict:
    return {s["id"]: s for s in result["scores"]}


def test_이상치가_가장_높은_점수를_받는다():
    result = score_samples(normal_population() + [outlier()], min_samples=30)
    assert result["status"] == "OK"
    assert result["model"].startswith("isolation_forest(scikit-learn")
    scores = by_id(result)
    top = scores["BANK_TRANSACTION:999"]
    others = [s["score"] for k, s in scores.items() if k != "BANK_TRANSACTION:999"]
    assert top["score"] > 0.5
    assert top["score"] > max(others)
    assert top["rawScore"] > 0.6
    # 평범한 거래 대부분은 ML 점수가 낮다
    assert np.median(others) < 0.2
    for s in scores.values():
        assert 0.0 <= s["score"] <= 1.0


def test_근거는_크게_벗어난_특성을_원래_값과_함께_보여준다():
    scores = by_id(score_samples(normal_population() + [outlier()], min_samples=30))
    top = scores["BANK_TRANSACTION:999"]["topFeatures"]
    assert 1 <= len(top) <= 3
    assert all(abs(f["zScore"]) >= 2 for f in top)
    assert [abs(f["zScore"]) for f in top] == sorted((abs(f["zScore"]) for f in top), reverse=True)
    names = {f["feature"] for f in top}
    assert names & {"amount", "amountRatioToAverage", "transactionHour", "averageAmount"}
    amount = next((f for f in top if f["feature"] == "amount"), None)
    if amount:
        assert amount["value"] == 4950000.0
        assert amount["median"] < 500000


def test_같은_입력이면_같은_점수():
    population = normal_population() + [outlier()]
    assert score_samples(population, 30)["scores"] == score_samples(population, 30)["scores"]


def test_표본이_부족하면_건너뛴다():
    result = score_samples(normal_population(10), min_samples=30)
    assert result["status"] == "INSUFFICIENT_DATA"
    assert result["scores"] == []
    assert "10건" in result["message"]


def test_없는_값은_대체값으로_변환한다():
    rows = [
        {"amount": "10000.00", "transactionHour": None, "weekday": 3, "merchantFrequency": 0,
         "averageAmount": None, "amountRatioToAverage": None, "monthlyTransactionCount": 1,
         "accountCategory": None, "daysSinceLastTransaction": None},
        {"amount": "bad", "weekday": 1},
    ]
    m = to_matrix(rows)
    assert m.shape == (2, len(FEATURES))
    assert np.isfinite(m).all()
    hour = FEATURES.index("transactionHour")
    ratio = FEATURES.index("amountRatioToAverage")
    average = FEATURES.index("averageAmount")
    assert m[0, hour] == -1
    assert m[0, ratio] == 0  # log(1)
    assert m[0, average] == m[0, FEATURES.index("amount")]  # 평균이 없으면 자기 금액


def test_계정과목은_모집단_빈도로_인코딩한다():
    rows = [{"accountCategory": "5400"}] * 3 + [{"accountCategory": "5900"}]
    col = to_matrix(rows)[:, FEATURES.index("accountCategory")]
    assert list(col) == [0.75, 0.75, 0.75, 0.25]


def test_상수_열은_편차가_없다():
    m = np.array([[1.0, 5.0], [1.0, 6.0], [1.0, 100.0]])
    z = robust_z(m)
    assert (z[:, 0] == 0).all()
    assert z[2, 1] > 2


def test_API():
    from app.main import app

    client = TestClient(app)  # with 블록 없이 → lifespan(OCR worker)을 띄우지 않는다
    body = {"samples": normal_population() + [outlier()], "minSamples": 30}
    res = client.post("/v1/anomaly/score", json=body)
    assert res.status_code == 200
    data = res.json()
    assert data["status"] == "OK"
    assert len(data["scores"]) == 81

    assert client.post("/v1/anomaly/score", json={"samples": [], "minSamples": 1}).status_code == 422
    small = client.post("/v1/anomaly/score", json={"samples": normal_population(5)})
    assert small.json()["status"] == "INSUFFICIENT_DATA"
