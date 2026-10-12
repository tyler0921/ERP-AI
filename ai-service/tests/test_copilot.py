"""AI Copilot: 규칙 Planner(spec 예시 질문), LLM 계획 검증·대체, 답변 검증·템플릿, worker 처리 (실제 Ollama 없이)."""

import json

import pytest

from app.copilot.planner import SCHEMA, make_plan, normalize_plan, parse_period, resolve_account, rule_plan, validate_plan
from app.llm.explain import explain, fallback, item_direction_conflicts, validate
from app.llm.provider import LlmError
from app.llm.worker import handle_job
from tests.test_llm import FakeProvider

ACCOUNTS = [
    {"code": "4010", "name": "상품매출", "type": "REVENUE"},
    {"code": "4020", "name": "용역매출", "type": "REVENUE"},
    {"code": "5100", "name": "급여", "type": "EXPENSE"},
    {"code": "5300", "name": "접대비", "type": "EXPENSE"},
    {"code": "5400", "name": "광고선전비", "type": "EXPENSE"},
    {"code": "5500", "name": "임차료", "type": "EXPENSE"},
    {"code": "5800", "name": "운반비", "type": "EXPENSE"},
]
CONTEXT = {"question": "", "today": "2026-10-12", "accounts": ACCOUNTS}


# ------------------------------------------------------------------ 규칙 Planner


@pytest.mark.parametrize(
    "question, expected",
    [
        # spec의 예시 질문
        ("이번 달 광고비가 얼마나 증가했어?",
         {"tool": "ACCOUNT_SUMMARY", "account": "광고선전비", "period": {"type": "THIS_MONTH"}}),
        ("가장 비용이 많이 증가한 거래처를 알려줘.", {"tool": "VENDOR_RANKING", "order": "INCREASE"}),
        ("이번 달 고위험 거래만 보여줘.", {"tool": "ANOMALY_LIST", "minLevel": "HIGH", "period": {"type": "THIS_MONTH"}}),
        # 그 밖의 표현
        ("9월 매출이랑 영업이익 전년 대비로 알려줘",
         {"tool": "PROFIT_SUMMARY", "period": {"type": "MONTH", "month": 9}, "compare": "PREV_YEAR"}),
        ("2026년 3분기 손익", {"tool": "PROFIT_SUMMARY", "period": {"type": "QUARTER", "year": 2026, "quarter": 3}}),
        ("작년 9월 운반비", {"tool": "ACCOUNT_SUMMARY", "account": "운반비", "period": {"type": "MONTH", "year": 2025, "month": 9}}),
        ("지난달 택배비 많이 쓴 업체 상위 3곳",
         {"tool": "VENDOR_RANKING", "account": "운반비", "limit": 3, "period": {"type": "LAST_MONTH"}}),
        ("9월 마감 어디까지 됐어?", {"tool": "CLOSING_STATUS", "period": {"type": "MONTH", "month": 9}}),
        ("심각한 이상거래 전체 10건", {"tool": "ANOMALY_LIST", "minLevel": "CRITICAL", "status": "ALL", "limit": 10}),
        ("전월 대비 줄어든 거래처", {"tool": "VENDOR_RANKING", "order": "DECREASE"}),  # "전월 대비"는 기간이 아니다
        ("상품매출 추이 12개월", {"tool": "ACCOUNT_SUMMARY", "account": "상품매출"}),
    ],
)
def test_규칙_Planner(question, expected):
    plan = rule_plan(question, ACCOUNTS, "2026-10-12")
    assert plan == expected
    assert validate_plan(plan, ACCOUNTS) == []


@pytest.mark.parametrize(
    "question, reason_part",
    [
        ("3분기 접대비가 가장 많은 부서는 어디야?", "부서"),
        ("다음 달 매출 예측해줘", "예측"),
        ("9월 광고비 전표 삭제해줘", "조회만"),
        ("안녕", "기간과 대상"),
    ],
)
def test_답할_수_없는_질문은_UNSUPPORTED(question, reason_part):
    plan = rule_plan(question, ACCOUNTS)
    assert plan["tool"] == "UNSUPPORTED"
    assert reason_part in plan["reason"]


def test_기간_표현():
    assert parse_period("12개월 추이") is None  # "개월"은 월이 아니다
    assert parse_period("이번 분기 비용") == {"type": "THIS_QUARTER"}
    assert parse_period("지난 분기") == {"type": "LAST_QUARTER"}
    assert parse_period("올해 매출") == {"type": "THIS_YEAR"}
    assert parse_period("작년 매출") == {"type": "LAST_YEAR"}
    assert parse_period("2025년 매출") == {"type": "YEAR", "year": 2025}
    assert parse_period("당월 손익") == {"type": "THIS_MONTH"}


def test_계정과목_찾기는_Spring과_같은_순서():
    assert resolve_account("5400", ACCOUNTS)["name"] == "광고선전비"
    assert resolve_account("광고비", ACCOUNTS)["name"] == "광고선전비"
    assert resolve_account("접대", ACCOUNTS)["name"] == "접대비"
    assert resolve_account("매출", ACCOUNTS) is None  # 상품매출·용역매출 둘 다
    assert resolve_account("부가세", ACCOUNTS) is None


# ------------------------------------------------------------------ LLM 계획


def test_LLM_계획이_형식에_맞으면_그대로_쓴다():
    plan = {"tool": "ACCOUNT_SUMMARY", "account": "광고선전비", "period": {"type": "MONTH", "month": 9}}
    provider = FakeProvider(json.dumps(plan, ensure_ascii=False))
    result = make_plan({**CONTEXT, "question": "9월 광고비?"}, provider)
    assert result["source"] == "LLM"
    assert result["plan"] == plan
    assert result["meta"]["promptVersion"] == "plan-v1"
    system, user, schema = provider.calls[0]
    assert schema == SCHEMA
    assert "SQL을 쓰지 않는다" in system
    assert "오늘: 2026-10-12" in user and "5400 광고선전비" in user and "질문: 9월 광고비?" in user


def test_LLM_계획을_질문과_맞춘다():
    # 실제로 7B 모델이 "9월"을 LAST_MONTH로 바꾼 적이 있다 → 기간은 규칙 파서로
    plan = {"tool": "ACCOUNT_SUMMARY", "account": "광고선전비", "period": {"type": "LAST_MONTH"}}
    fixed, corrections = normalize_plan(plan, "9월 광고비가 얼마나 증가했어?", "2026-10-12", ACCOUNTS)
    assert fixed["period"] == {"type": "MONTH", "month": 9}
    assert corrections == [{"field": "period", "llm": {"type": "LAST_MONTH"}, "rule": {"type": "MONTH", "month": 9}}]
    assert normalize_plan(plan, "지난달 광고비", "2026-10-12", ACCOUNTS) == (plan, [])
    wrong_year = {"tool": "PROFIT_SUMMARY", "period": {"type": "MONTH", "year": 2024, "month": 9}}
    assert normalize_plan(wrong_year, "작년 9월 손익", "2026-10-12")[0]["period"] == {"type": "MONTH", "year": 2025, "month": 9}
    assert normalize_plan(wrong_year, "2024년 9월 손익", "2026-10-12") == (wrong_year, [])
    # 질문에 기간 표현이 없으면 LLM 기간을 두되 질문에 없는 연도는 뺀다
    assert normalize_plan(wrong_year, "추석 있던 달 손익", "2026-10-12")[0]["period"] == {"type": "MONTH", "month": 9}
    unsupported = {"tool": "UNSUPPORTED", "reason": "부서"}
    assert normalize_plan(unsupported, "3분기 부서별", "2026-10-12") == (unsupported, [])


def test_비교_기준_뒤쪽이_조회_기간이고_계정과목을_무시하면_바로잡는다():
    question = "작년 9월 대비 올해 9월 소모품비"
    assert parse_period(question) == {"type": "MONTH", "month": 9}
    assert rule_plan(question, ACCOUNTS + [{"code": "5600", "name": "소모품비", "type": "EXPENSE"}], "2026-10-12") == {
        "tool": "ACCOUNT_SUMMARY", "account": "소모품비", "period": {"type": "MONTH", "month": 9}, "compare": "PREV_YEAR"}
    # 실제 출력: 7B 모델이 손익 요약 + 작년 9월로 해석했다
    llm = {"tool": "PROFIT_SUMMARY", "period": {"type": "MONTH", "year": 2025, "month": 9}, "compare": "PREV_YEAR"}
    accounts = ACCOUNTS + [{"code": "5600", "name": "소모품비", "type": "EXPENSE"}]
    result = make_plan({**CONTEXT, "accounts": accounts, "question": question}, FakeProvider(json.dumps(llm)))
    assert result["source"] == "LLM"
    assert result["plan"] == {"tool": "ACCOUNT_SUMMARY", "account": "소모품비", "period": {"type": "MONTH", "month": 9},
                              "compare": "PREV_YEAR"}
    assert [c["field"] for c in result["meta"]["corrections"]] == ["tool", "period"]
    # 손익을 물었으면 그대로 둔다
    keep = {"tool": "PROFIT_SUMMARY", "period": {"type": "QUARTER", "quarter": 3}}
    assert normalize_plan(keep, "3분기 광고선전비 포함 손익", "2026-10-12", ACCOUNTS) == (keep, [])


@pytest.mark.parametrize(
    "llm_output, issue_part",
    [
        ({"tool": "RUN_SQL", "sql": "DELETE FROM journal_entries"}, "tool"),
        ({"tool": "ACCOUNT_SUMMARY", "account": "부가세"}, "계정과목 목록에 없는"),
        ({"tool": "ACCOUNT_SUMMARY", "account": "광고선전비", "compare": "YESTERDAY"}, "compare"),
        ({"tool": "ANOMALY_LIST", "limit": 1000}, "limit"),
        ("이번 달 광고비는 5,000,000원입니다", "JSON"),
    ],
)
def test_LLM_계획이_틀리면_규칙_Planner로_대체한다(llm_output, issue_part):
    content = llm_output if isinstance(llm_output, str) else json.dumps(llm_output, ensure_ascii=False)
    result = make_plan({**CONTEXT, "question": "이번 달 광고비가 얼마나 증가했어?"}, FakeProvider(content))
    assert result["source"] == "RULE"
    assert result["plan"]["tool"] == "ACCOUNT_SUMMARY"
    assert any(issue_part in i for i in result["meta"]["llmIssues"])
    assert "llmPlan" in result["meta"]


def test_기간_형식_오류는_질문에_기간이_없을_때_대체로_이어진다():
    bad = {"tool": "PROFIT_SUMMARY", "period": {"type": "MONTH", "month": 13}}
    result = make_plan({**CONTEXT, "question": "손익 알려줘"}, FakeProvider(json.dumps(bad)))
    assert result["source"] == "RULE"
    assert any("period.month" in i for i in result["meta"]["llmIssues"])


def test_LLM_장애면_규칙_Planner():
    result = make_plan({**CONTEXT, "question": "이번 달 고위험 거래"}, FakeProvider(error=LlmError("Ollama 호출 실패: ReadTimeout")))
    assert result["source"] == "RULE"
    assert result["plan"]["tool"] == "ANOMALY_LIST"
    assert "ReadTimeout" in result["meta"]["llmError"]


# ------------------------------------------------------------------ 답변

# Spring CopilotFacts.build와 같은 모양 (가상)
FACTS = {
    "질문": "지난달 비용이 가장 많이 늘어난 거래처는?",
    "해석": {"조회": "거래처별 비용 (증가액 순)", "기간": "2026년 9월", "비교 기간": "2026년 8월"},
    "조회 결과": [{"표": "거래처", "행 수": 2, "행": [
        {"순위": "1", "거래처": "(주)가상광고", "당기": "5,000,000원", "비교 기간": "1,200,000원", "증감": "3,800,000원", "증감률": "+316.7%"},
        {"순위": "2", "거래처": "한빛문구", "당기": "180,000원", "비교 기간": "175,000원", "증감": "5,000원", "증감률": "+2.9%"},
    ]}],
    "요약": [
        "2026년 9월 2026년 8월 대비 비용이 가장 많이 늘어난 거래처는 (주)가상광고로, 1,200,000원에서 5,000,000원으로 3,800,000원 증가 (+316.7%)입니다.",
        "이어서 한빛문구(5,000원 증가 (+2.9%)) 순입니다.",
    ],
    "증감 방향": [{"항목": "(주)가상광고", "방향": "증가"}, {"항목": "한빛문구", "방향": "증가"}],
}


def test_근거대로_쓴_답변은_통과한다():
    good = {"explanation": "2026년 9월에 비용이 가장 많이 늘어난 거래처는 (주)가상광고로, 8월 1,200,000원에서 5,000,000원으로 "
                           "3,800,000원 증가했습니다. 다음은 한빛문구로 5,000원 늘었습니다.", "checkpoints": []}
    result = explain("COPILOT_ANSWER", FACTS, FakeProvider(json.dumps(good, ensure_ascii=False)))
    assert result["status"] == "GENERATED", result["meta"]
    assert result["meta"]["promptVersion"] == "copilot-v1"


def test_항목의_증감_방향을_뒤집으면_대체한다():
    text = "(주)가상광고는 1,200,000원에서 5,000,000원으로 줄었습니다. 한빛문구는 5,000원 늘었습니다."
    assert item_direction_conflicts(text, FACTS) == ["(주)가상광고 감소"]
    assert "DIRECTION_CONFLICT" in [i["code"] for i in validate(text, [], FACTS)]
    # 계정 코드는 떼고 찾는다
    facts = {"증감 방향": [{"항목": "5400 광고선전비", "방향": "증가"}]}
    assert item_direction_conflicts("광고선전비가 크게 감소했습니다.", facts) == ["광고선전비 감소"]
    assert item_direction_conflicts("광고선전비는 늘었고 다른 비용은 줄었습니다.", facts) == []


def test_근거에_없는_순위_표현은_거부한다():
    # 실제 출력: 계정 하나만 조회했는데 "가장 큰 증가액"이라고 덧붙였다
    facts = {"질문": "9월 광고비가 얼마나 증가했어?",
             "요약": ["2026년 9월 광고선전비는 5,000,000원이며, 2026년 8월 1,200,000원 대비 3,800,000원 증가 (+316.7%)입니다."],
             "증감 방향": [{"항목": "광고선전비", "방향": "증가"}]}
    text = "9월 광고비는 3,800,000원 증가하여 316.7% 상승했습니다. 이는 가장 큰 증가액을 기록한 항목입니다."
    assert "UNSUPPORTED_CLAIM" in [i["code"] for i in validate(text, [], facts, copilot=True)]
    assert validate(text, [], facts) == []  # 다른 설명(이상거래·월마감)에는 적용하지 않는다
    result = explain("COPILOT_ANSWER", facts, FakeProvider(json.dumps({"explanation": text, "checkpoints": []}, ensure_ascii=False)))
    assert result["status"] == "FALLBACK"
    # 근거에 "가장"이 있으면 허용 (거래처 순위 요약)
    assert validate("비용이 가장 많이 늘어난 거래처는 (주)가상광고로 3,800,000원 증가했습니다.", [], FACTS, copilot=True) == []


def test_금액_없이_추론만_한_답변은_거부한다():
    # 실제 출력: 숫자 없이 앞뒤가 맞지 않는 해석만 늘어놓았다
    facts = {"질문": "3분기 매출과 영업이익은 전년 대비 어때?",
             "요약": ["2026년 3분기 매출은 32,000,000원이며, 2025년 3분기 10,000,000원 대비 22,000,000원 증가 (+220.0%)입니다."]}
    text = "2026년 3분기 매출과 영업이익은 전년 대비 증가하였으나, 비용 증가율이 매출 증가율보다 높아 영업이익 증가율이 더 높게 나타났습니다."
    assert "NO_FIGURES" in [i["code"] for i in validate(text, [], facts, copilot=True)]
    assert validate("2026년 3분기 매출은 32,000,000원으로 22,000,000원 늘었습니다.", [], facts, copilot=True) == []
    # 요약에 금액이 없으면(건수·진행률) 요구하지 않는다
    count = {"요약": ["2026년 10월 이상거래는 0건입니다."]}
    assert validate("2026년 10월에 해당하는 이상거래는 0건입니다.", [], count, copilot=True) == []


def test_답변_템플릿은_Spring_요약_그대로():
    text, checkpoints = fallback("COPILOT_ANSWER", FACTS)
    assert text == " ".join(FACTS["요약"])
    assert checkpoints == []
    assert validate(text, checkpoints, FACTS) == []
    made_up = {"explanation": "가상광고 비용은 4.2배가 되었고 평균은 2,590,000원입니다.", "checkpoints": []}
    result = explain("COPILOT_ANSWER", FACTS, FakeProvider(json.dumps(made_up, ensure_ascii=False)))
    assert result["status"] == "FALLBACK"
    assert result["text"] == text


# ------------------------------------------------------------------ worker


def test_worker가_계획_작업을_계획_콜백으로_보낸다():
    job = {"explanationId": 21, "companyId": 3, "attempt": 2, "targetType": "COPILOT_PLAN",
           "facts": {**CONTEXT, "question": "이번 달 고위험 거래만 보여줘."}}
    path, payload = handle_job(json.dumps(job, ensure_ascii=False), FakeProvider(error=LlmError("down")))
    assert path == "/internal/v1/copilot/questions/21/plan"
    assert payload["attempt"] == 2 and payload["success"] is True
    assert payload["source"] == "RULE" and payload["plan"]["tool"] == "ANOMALY_LIST"

    answer = {**job, "targetType": "COPILOT_ANSWER", "facts": FACTS}
    path, payload = handle_job(json.dumps(answer, ensure_ascii=False), FakeProvider(error=LlmError("down")))
    assert path == "/internal/v1/explanations/21/result"
    assert payload["status"] == "FALLBACK"
