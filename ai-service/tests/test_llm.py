"""LLM 설명: 출력 검증, 템플릿 대체, worker 메시지 처리, Ollama 요청 형식 (실제 Ollama 없이)."""

import json

import httpx
import pytest

from app.llm.explain import SCHEMA, allowed_numbers, explain, fallback, validate
from app.llm.provider import LlmError, LlmReply, OllamaProvider
from app.llm.worker import handle_job

# Spring AnomalyExplanationFacts.forResult와 같은 모양의 근거 데이터 (가상)
FACTS = {
    "거래": {
        "원천": "은행 거래",
        "거래일시": "2026-09-08 10:12",
        "요일": "화요일",
        "거래처": "가상컨설팅",
        "출금액": "4,950,000원",
        "적요": "자문료",
        "계정과목": "연결된 전표 없음",
        "증빙": "없음",
    },
    "위험도": {"위험도 점수": 100, "위험 수준": "심각", "규칙 점수": 100, "ML 가산 점수": 17},
    "규칙 근거": [
        {"코드": "SPLIT_PAYMENT", "규칙": "쪼개기 결제 의심", "배점": 45,
         "사유": "같은 날 같은 거래처(가상컨설팅)에 승인 기준(5,000,000원) 미만 출금이 2건이고, 합계(9,750,000원)는 기준 이상입니다."},
        {"코드": "NEAR_LIMIT", "규칙": "승인 기준 직전 금액 반복", "배점": 40,
         "사유": "승인 기준(5,000,000원)의 90% 이상~기준 미만 금액이고, 30일 이내에 이런 출금이 3건 있습니다."},
        {"코드": "MISSING_EVIDENCE", "규칙": "증빙 없는 거래", "배점": 20,
         "사유": "30,000원 초과 출금이지만 확정 대사된 전표가 없어 증빙을 확인할 수 없습니다."},
    ],
    "ML 근거": {"상태": "반영", "이상 점수": "41%",
               "모집단에서 크게 벗어난 특성": [{"특성": "금액", "이 거래": "4,950,000원", "모집단 중앙값": "108,050원"}]},
}

SCAN_FACTS = {
    "분석 기간": "2026-08-01 ~ 2026-10-08",
    "분석한 출금 건수": 45,
    "탐지 건수(40점 이상)": 7,
    "위험 수준별 건수": {"심각": 3, "높음": 2, "중간": 2},
    "규칙별 탐지 건수": [{"규칙": "증빙 없는 거래", "건수": 7}, {"규칙": "승인 기준 직전 금액 반복", "건수": 3}],
    "ML": "반영",
    "위험도 상위 거래": [
        {"거래일": "2026-09-08", "거래처": "가상컨설팅", "출금액": "4,950,000원", "위험도 점수": 100, "위험 수준": "심각",
         "근거": ["쪼개기 결제 의심"]},
    ],
}

GOOD = {
    "explanation": "가상컨설팅에 대한 4,950,000원 출금은 같은 날 승인 기준(5,000,000원) 미만으로 나뉜 출금이 2건이고 "
    "합계가 9,750,000원이라 분할 결제 여부를 확인할 필요가 있습니다. 승인 기준 직전 금액이 30일 이내에 3건 있었고, "
    "확정 대사된 전표가 없어 증빙도 확인되지 않습니다. ML 이상 점수는 41%입니다.",
    "checkpoints": ["분할 결제 여부 확인", "자문 계약서와 결재 내역 확인"],
}


class FakeProvider:
    name = "fake"
    model = "fake-model"

    def __init__(self, content=None, error=None):
        self.content = content
        self.error = error
        self.calls = []

    def chat(self, system, user, schema):
        self.calls.append((system, user, schema))
        if self.error:
            raise self.error
        return LlmReply(content=self.content, duration_ms=1234)


def test_근거를_그대로_쓴_설명은_통과한다():
    provider = FakeProvider(json.dumps(GOOD, ensure_ascii=False))
    result = explain("ANOMALY_RESULT", FACTS, provider)
    assert result["status"] == "GENERATED"
    assert result["text"] == GOOD["explanation"]
    assert result["checkpoints"] == GOOD["checkpoints"]
    assert result["meta"] == {"provider": "fake", "model": "fake-model", "promptVersion": "anomaly-v2",
                              "durationMs": 1234, "validation": []}
    system, user, schema = provider.calls[0]
    assert "최종 판단하지 않는다" in system
    assert "4,950,000원" in user  # 근거 데이터를 그대로 전달
    assert schema == SCHEMA


def test_만원_단위와_반올림_표기는_같은_숫자로_본다():
    allowed = allowed_numbers(FACTS)
    text = "가상컨설팅에 495만원을 출금했고 같은 날 합계는 975만원입니다. 확인이 필요합니다."
    assert validate(text, [], FACTS) == []
    assert all(n in allowed for n in map(__import__("decimal").Decimal, ["495", "975", "41", "2026", "9", "8"]))


def test_근거에_없는_숫자를_만들면_템플릿으로_대체한다():
    made_up = dict(GOOD, explanation=GOOD["explanation"] + " 평균보다 7.6배 큰 금액이며 차액은 4,841,950원입니다.")
    result = explain("ANOMALY_RESULT", FACTS, FakeProvider(json.dumps(made_up, ensure_ascii=False)))
    assert result["status"] == "FALLBACK"
    issue = result["meta"]["validation"][0]
    assert issue["code"] == "UNSUPPORTED_NUMBER"
    assert "4841950" in issue["message"] and "7.6" in issue["message"]
    assert "차액은" in result["meta"]["rejectedText"]
    # 템플릿 설명은 근거 문장을 그대로 옮긴다
    assert result["text"].startswith("가상컨설팅에 대한 4,950,000원 출금(2026-09-08 10:12)이 위험도 100점(심각)으로")
    assert "분할 결제" in result["checkpoints"][0]


@pytest.mark.parametrize(
    "text, code",
    [
        ("가상컨설팅 출금은 명백한 횡령으로 보이며 4,950,000원이 빠져나갔습니다.", "VERDICT_LANGUAGE"),
        ("가상컨설팅에 대한 4,950,000원 출금은 分割 결제가 의심되어 확인이 필요합니다.", "LANGUAGE"),
        ("This payment of 4,950,000 looks suspicious and should be checked.", "LANGUAGE"),
        ("확인 필요", "LENGTH"),
        ("가상컨설팅에 대한 4,950,000원 출금은 승인 기준을 초과해 확인이 필요합니다.", "COMPARISON_CONFLICT"),
    ],
)
def test_단정_표현_외국_문자_길이(text, code):
    assert code in [i["code"] for i in validate(text, [], FACTS)]


def test_근거에_있는_기준_초과_표현은_허용한다():
    facts = {"규칙 근거": [{"사유": "합계가 승인 기준을 초과합니다. 4,950,000원"}]}
    assert validate("합계가 승인 기준을 초과하는 4,950,000원 출금이라 확인이 필요합니다.", [], facts) == []


def test_JSON이_아니면_대체한다():
    result = explain("ANOMALY_RESULT", FACTS, FakeProvider("가상컨설팅 출금은 확인이 필요합니다."))
    assert result["status"] == "FALLBACK"
    assert result["meta"]["validation"][0]["code"] == "INVALID_OUTPUT"


def test_LLM_서버_장애면_템플릿_설명():
    result = explain("ANOMALY_RESULT", FACTS, FakeProvider(error=LlmError("Ollama 호출 실패: ConnectError")))
    assert result["status"] == "FALLBACK"
    assert "ConnectError" in result["meta"]["llmError"]
    assert "ML 이상 점수는 41%입니다." in result["text"]


def test_템플릿_설명은_항상_검증을_통과한다():
    for target, facts in (("ANOMALY_RESULT", FACTS), ("ANOMALY_SCAN", SCAN_FACTS)):
        text, checkpoints = fallback(target, facts)
        issues = [i for i in validate(text, checkpoints, facts) if i["code"] != "LANGUAGE"]
        assert issues == [], (target, text, issues)


def test_분석_요약_템플릿():
    text, checkpoints = fallback("ANOMALY_SCAN", SCAN_FACTS)
    assert text.startswith("2026-08-01 ~ 2026-10-08 동안 출금 45건을 분석해 7건을")
    assert "심각 3건" in text and "증빙 없는 거래(7건)" in text
    assert checkpoints == ["가상컨설팅 4,950,000원 출금 확인 (위험도 100점)"]


def test_worker_메시지_처리():
    job = {"explanationId": 7, "companyId": 1, "attempt": 2, "targetType": "ANOMALY_SCAN", "facts": SCAN_FACTS}
    path, payload = handle_job(json.dumps(job, ensure_ascii=False), FakeProvider(error=LlmError("down")))
    assert path == "/internal/v1/explanations/7/result"
    assert payload["attempt"] == 2 and payload["success"] is True and payload["status"] == "FALLBACK"
    assert payload["meta"]["promptVersion"] == "scan-v2"

    assert handle_job(b"not json", FakeProvider()) is None
    assert handle_job(json.dumps({**job, "targetType": "DOCUMENT"}), FakeProvider()) is None


def test_Ollama_요청_형식과_오류():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/tags":
            return httpx.Response(200, json={"models": [{"name": "qwen2.5:7b-instruct"}]})
        seen.update(json.loads(request.content))
        return httpx.Response(200, json={"message": {"role": "assistant", "content": json.dumps(GOOD)}})

    provider = OllamaProvider("http://ollama:11434/", "qwen2.5:7b-instruct", transport=httpx.MockTransport(handler))
    reply = provider.chat("sys", "user", SCHEMA)
    assert json.loads(reply.content) == GOOD
    assert seen["stream"] is False and seen["format"] == SCHEMA
    assert seen["options"]["temperature"] == 0.2 and seen["options"]["seed"] == 42
    assert [m["role"] for m in seen["messages"]] == ["system", "user"]
    assert provider.has_model()

    broken = OllamaProvider("http://ollama:11434", "m", transport=httpx.MockTransport(lambda r: httpx.Response(500)))
    with pytest.raises(LlmError):
        broken.chat("s", "u", SCHEMA)
