"""월마감 설명(CLOSING_MONTH): 프롬프트, 증감 방향 검증, 템플릿 설명, worker 처리 (실제 Ollama 없이)."""

import json

import pytest

from app.llm.explain import direction_conflicts, explain, fallback, validate
from app.llm.provider import LlmError
from app.llm.worker import handle_job
from tests.test_llm import FakeProvider

# Spring ClosingFacts.build와 같은 모양의 근거 데이터 (가상)
FACTS = {
    "기준월": "2026-09",
    "비교 기준": {"전월": "2026-08", "전년 동월": "2025-09"},
    "손익 요약": [
        {"항목": "매출", "당월": "20,000,000원", "전월": "12,000,000원", "전년 동월": "10,000,000원",
         "전월 대비": "8,000,000원 증가 (+66.7%)", "전년 동월 대비": "10,000,000원 증가 (+100.0%)"},
        {"항목": "비용", "당월": "10,000,000원", "전월": "4,000,000원", "전년 동월": "3,000,000원",
         "전월 대비": "6,000,000원 증가 (+150.0%)", "전년 동월 대비": "7,000,000원 증가 (+233.3%)"},
        {"항목": "영업이익", "당월": "10,000,000원", "전월": "8,000,000원", "전년 동월": "7,000,000원",
         "전월 대비": "2,000,000원 증가 (+25.0%)", "전년 동월 대비": "3,000,000원 증가 (+42.9%)"},
    ],
    "주요 변동": [
        {"항목": "4010 상품매출", "구분": "계정과목", "비교": "전년 동월",
         "내용": "4010 상품매출: 전년 동월 10,000,000원 → 당월 20,000,000원, 10,000,000원 증가 (+100.0%)"},
        {"항목": "5400 광고선전비", "구분": "계정과목", "비교": "전월",
         "내용": "5400 광고선전비: 전월 1,000,000원 → 당월 5,000,000원, 4,000,000원 증가 (+400.0%)"},
        {"항목": "5500 임차료", "구분": "계정과목", "비교": "전월",
         "내용": "5500 임차료: 전월에는 없다가 당월 2,000,000원 발생"},
    ],
    "이상거래 현황": {"검토 대기": 2, "검토 대기 중 고위험(높음 이상)": 1, "이상 확정": 0},
    "마감 체크리스트": {"진행률": "2/6 완료",
                  "남은 항목": ["증빙 검토 (1건)", "거래 전표 연결 (1건)", "은행 대사 (2건)", "이상거래 분석"]},
}

GOOD = {
    "explanation": "2026-09 매출은 20,000,000원으로 전월 대비 8,000,000원 증가 (+66.7%)했고, 비용은 10,000,000원으로 "
    "전월 대비 6,000,000원 증가했으며, 영업이익은 10,000,000원입니다. 광고선전비가 전월 1,000,000원에서 당월 "
    "5,000,000원으로 늘었고 임차료가 당월 2,000,000원 새로 발생했습니다. 검토 대기 이상거래가 2건 있고, "
    "마감 체크리스트는 2/6 완료입니다.",
    "checkpoints": ["광고선전비 증가 사유 확인", "은행 대사 처리"],
}


def test_근거를_그대로_쓴_월마감_설명은_통과한다():
    provider = FakeProvider(json.dumps(GOOD, ensure_ascii=False))
    result = explain("CLOSING_MONTH", FACTS, provider)
    assert result["status"] == "GENERATED", result["meta"]
    assert result["meta"]["promptVersion"] == "closing-v1"
    system, user, _ = provider.calls[0]
    assert "월마감" in system and "원인을 추측하지 않는다" in system
    assert "8,000,000원 증가 (+66.7%)" in user


@pytest.mark.parametrize(
    "text, conflict",
    [
        ("매출은 20,000,000원으로 전월 대비 8,000,000원 감소했습니다.", "매출 전월 대비 감소"),
        ("영업이익은 10,000,000원으로 전년 동월보다 3,000,000원 감소했습니다.", "영업이익 전년 대비 감소"),
        # 다른 합계의 방향 낱말은 섞지 않는다: 매출 조각은 맞고, 비용 조각이 틀렸다
        ("매출은 전월보다 증가했지만 비용은 전월 대비 감소했습니다.", "비용 전월 대비 감소"),
    ],
)
def test_증감_방향을_뒤집으면_충돌(text, conflict):
    assert direction_conflicts(text, FACTS) == [conflict]
    assert "DIRECTION_CONFLICT" in [i["code"] for i in validate(text, [], FACTS)]


@pytest.mark.parametrize(
    "text",
    [
        "매출은 전월 대비 증가했고 비용도 전월 대비 증가했습니다.",
        # 상품매출은 합계 이름이 아니다
        "상품매출은 전월 대비 감소한 거래처가 있어 확인이 필요합니다.",
        # 비교 기준이 둘 다 있거나 없으면 판단하지 않는다
        "매출은 전월과 전년 동월 모두와 비교해 감소 없이 늘었습니다.",
        "매출 감소 여부를 확인해야 합니다.",
    ],
)
def test_애매하거나_맞는_방향은_통과(text):
    assert direction_conflicts(text, FACTS) == []


def test_근거_밖의_숫자와_원인_단정은_대체한다():
    made_up = dict(GOOD, explanation=GOOD["explanation"] + " 매출 대비 비용 비율은 50.5%입니다.")
    result = explain("CLOSING_MONTH", FACTS, FakeProvider(json.dumps(made_up, ensure_ascii=False)))
    assert result["status"] == "FALLBACK"
    assert result["meta"]["validation"][0]["code"] == "UNSUPPORTED_NUMBER"


def test_월마감_템플릿():
    text, checkpoints = fallback("CLOSING_MONTH", FACTS)
    assert text.startswith("2026-09 매출은 20,000,000원(전월 대비 8,000,000원 증가 (+66.7%)), 비용은 10,000,000원")
    assert "영업이익은 10,000,000원(전월 대비 2,000,000원 증가 (+25.0%))입니다." in text
    assert "주요 변동은 4010 상품매출: 전년 동월 10,000,000원" in text
    assert "검토 대기 이상거래가 2건(고위험 1건) 있습니다." in text
    assert "마감 체크리스트는 2/6 완료이며 남은 항목은 증빙 검토 (1건), 거래 전표 연결 (1건)" in text
    assert checkpoints == ["증빙 검토 (1건) 처리", "거래 전표 연결 (1건) 처리", "4010 상품매출 변동 사유 확인"]
    # 템플릿도 검증 규칙을 통과한다
    assert validate(text, checkpoints, FACTS) == []


def test_변동과_남은_항목이_없는_달의_템플릿():
    facts = dict(FACTS, **{"주요 변동": [], "이상거래 현황": {"검토 대기": 0},
                         "마감 체크리스트": {"진행률": "6/6 완료", "남은 항목": []}})
    text, checkpoints = fallback("CLOSING_MONTH", facts)
    assert "탐지 기준에 해당하는 주요 변동은 없습니다." in text
    assert "검토 대기" not in text
    assert text.endswith("마감 체크리스트는 6/6 완료입니다.")
    assert checkpoints == []
    assert validate(text, checkpoints, facts) == []


def test_worker가_월마감_작업을_처리한다():
    job = {"explanationId": 11, "companyId": 3, "attempt": 1, "targetType": "CLOSING_MONTH", "facts": FACTS}
    path, payload = handle_job(json.dumps(job, ensure_ascii=False), FakeProvider(error=LlmError("down")))
    assert path == "/internal/v1/explanations/11/result"
    assert payload["success"] is True and payload["status"] == "FALLBACK"
    assert payload["meta"]["promptVersion"] == "closing-v1"
    assert payload["text"].startswith("2026-09 매출은")
