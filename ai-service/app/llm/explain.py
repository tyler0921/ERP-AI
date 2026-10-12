"""이상거래·분석 요약·월마감 설명: 프롬프트 → LLM → 출력 검증 → (실패 시) 템플릿 설명.

LLM은 Spring이 계산해 보낸 근거 데이터(facts)를 문장으로 옮기기만 한다.
근거에 없는 숫자, 단정적인 판정 표현, 외국 문자 섞임은 규칙으로 걸러내고, 그때는 규칙 근거로 만든 템플릿 설명을 쓴다.
"""

from __future__ import annotations

import json
import re
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation
from typing import Any, Iterable

from app.llm.provider import LlmError, LlmProvider

PROMPT_VERSION = {"ANOMALY_RESULT": "anomaly-v2", "ANOMALY_SCAN": "scan-v2", "CLOSING_MONTH": "closing-v1"}
TARGET_TYPES = tuple(PROMPT_VERSION)

SCHEMA = {
    "type": "object",
    "properties": {
        "explanation": {"type": "string"},
        "checkpoints": {"type": "array", "items": {"type": "string"}, "maxItems": 3},
    },
    "required": ["explanation", "checkpoints"],
}

_COMMON_RULES = """반드시 지킬 것:
- 근거 데이터에 없는 사실이나 숫자를 만들지 않는다. 숫자를 새로 계산하지 않는다(합계·차이·비율 계산 금지). 금액·날짜·점수는 근거 데이터의 표기 그대로 쓴다.
- 규칙 근거의 '사유'는 확인된 사실이다. 사유에 적힌 대상(이 출금·합계·건수)과 비교 관계(이상·미만·초과·직전)를 바꾸지 말고 그대로 옮긴다.
  예: 사유가 '기준 미만 출금이 2건이고 합계는 기준 이상'이면 '이 출금이 기준을 초과했다'고 쓰면 틀린 설명이다.
- 이상거래인지 최종 판단하지 않는다. '사기', '횡령', '불법', '위반' 같은 단정적인 표현 대신 '해당합니다', '확인이 필요합니다'처럼 쓴다.
- 한국어로만 쓴다. 한자나 영어 문장을 섞지 않는다. 영문 대문자 규칙 코드는 쓰지 않는다.
- 출금은 회사가 거래처'에' 지급한 돈이다 ('거래처로부터'라고 쓰지 않는다).
- JSON으로만 답한다."""

# 형식을 보여주는 예시. 실제 근거와 섞이지 않도록 다른 거래처·금액을 쓴다 (섞여 나오면 숫자 검증에서 걸러진다).
_EXAMPLE = """예시
근거 데이터의 규칙 근거: [동일 금액 중복 결제(40점): 같은 거래처(한빛상사)에 같은 금액 820,000원 출금이 3일 이내에 1건 더 있습니다. / 증빙 없는 거래(20점): 연결된 전표에 승인된 증빙이 없습니다.]
좋은 답: {"explanation": "한빛상사에 같은 금액 820,000원 출금이 3일 이내에 1건 더 있어 동일 금액 중복 결제에 해당합니다. 연결된 전표에 승인된 증빙도 없어 지급 근거를 확인할 필요가 있습니다.", "checkpoints": ["두 출금이 같은 발주에 대한 지급인지 확인", "세금계산서 등 증빙 수취"]}"""

SYSTEM = {
    "ANOMALY_RESULT": "너는 회계 담당자를 돕는 감사 보조원이다. 사용자가 주는 '근거 데이터'만 사용해, "
    "이 출금 거래가 왜 이상거래 후보로 탐지되었는지 설명한다.\n\n" + _COMMON_RULES + """
- explanation: 2~4문장. 배점이 큰 규칙 근거부터 최대 3개를 빠짐없이 설명하고, ML 근거가 반영되었으면 한 문장으로 덧붙인다.
  ML 문장은 '모집단에서 크게 벗어난 특성'의 특성 이름과 두 값(이 거래, 모집단 중앙값)만 쓰고, 다른 해석을 붙이지 않는다.
- checkpoints: 담당자가 할 일 1~3개. '~확인', '~수취'처럼 행동으로 끝나는 짧은 구절.

""" + _EXAMPLE,
    "ANOMALY_SCAN": "너는 회계 담당자를 돕는 감사 보조원이다. 사용자가 주는 '근거 데이터'만 사용해, "
    "이번 이상거래 분석 결과를 요약한다.\n\n" + _COMMON_RULES + """
- explanation: 3~5문장. 탐지 건수와 위험 수준별 건수, 가장 많이 걸린 근거, 위험도가 가장 높은 거래를 언급한다.
- checkpoints: 우선 할 일 1~3개. '~확인', '~수취'처럼 행동으로 끝나는 짧은 구절.""",
    "CLOSING_MONTH": "너는 월마감 담당자를 돕는 회계 보조원이다. 사용자가 주는 '근거 데이터'만 사용해, "
    "이번 달 손익이 왜 이렇게 나왔는지와 마감 전에 남은 일을 요약한다.\n\n" + """반드시 지킬 것:
- 근거 데이터에 없는 사실이나 숫자를 만들지 않는다. 숫자를 새로 계산하지 않는다(합계·차이·비율 계산 금지). 금액·증감률은 근거 데이터의 표기 그대로 쓴다.
- '전월 대비', '전년 동월 대비', '주요 변동'의 '내용'은 이미 계산된 사실이다. 증가·감소 방향과 비교 기준(전월, 전년 동월)을 바꾸지 말고 그대로 옮긴다.
- 변동의 원인을 추측하지 않는다. 원인은 '확인이 필요합니다'처럼 쓴다. '분식', '횡령', '오류' 같은 단정적인 표현을 쓰지 않는다.
- 한국어로만 쓴다. 한자나 영어 문장을 섞지 않는다. 영문 대문자 코드는 쓰지 않는다.
- JSON으로만 답한다.
- explanation: 3~5문장. 매출·비용·영업이익의 당월 금액과 전월 대비 변동, 주요 변동 상위 2~3개, 검토 대기 이상거래(있으면), 마감 체크리스트 진행률과 남은 항목 순서로 쓴다.
- checkpoints: 마감 전에 할 일 1~3개. 남은 체크리스트 항목과 큰 변동의 사유 확인을 '~확인', '~처리'처럼 행동으로 끝나는 짧은 구절로 쓴다.""",
}

# 템플릿 설명의 확인 사항 (규칙 코드별)
CHECKPOINT_BY_RULE = {
    "DUPLICATE_PAYMENT": "같은 금액 결제가 중복 지급인지 확인",
    "SPLIT_PAYMENT": "승인 기준을 피하려는 분할 결제인지 확인",
    "NEAR_LIMIT": "승인 기준 직전 금액의 결재 내역 확인",
    "AMOUNT_SPIKE": "평소보다 큰 금액의 사유와 계약서 확인",
    "NEW_VENDOR_HIGH": "신규 거래처의 등록 절차와 실재 여부 확인",
    "DUPLICATE_RECEIPT": "같은 증빙이 두 번 처리되었는지 확인",
    "MISSING_EVIDENCE": "세금계산서·영수증 등 적격 증빙 수취",
    "VENDOR_REPEAT": "짧은 기간 반복 결제의 사유 확인",
    "OFF_HOURS": "업무 시간 외 결제의 사용 목적 확인",
    "ACCOUNT_SURGE": "계정과목 지출이 늘어난 사유 확인",
}

MIN_LENGTH = 20
MAX_LENGTH = 800
MAX_CHECKPOINT_LENGTH = 120
_NUMBER = re.compile(r"\d[\d,]*(?:\.\d+)?")
_VERDICT = re.compile(r"사기|횡령|불법|범죄|부정\s*행위|위반")
_FOREIGN = re.compile(r"[㐀-鿿豈-﫿぀-ヿ]")  # 한자·일본 가나
_HANGUL = re.compile(r"[가-힣]")
# "기준을 초과/넘음" 주장. 7B 모델이 "기준 미만 출금"을 "기준 초과"로 뒤집는 오류가 반복되어, 근거에 같은 표현이 있을 때만 허용한다
_EXCEEDS = re.compile(r"기준\s*(?:을|를)?\s*(?:초과|넘)")
# 근거와 무관하게 써도 되는 작은 수 ("두 가지", "1~3개" 등)
_ALWAYS_ALLOWED = {Decimal(n) for n in range(0, 11)}


# ------------------------------------------------------------------ 숫자 검증


def _numbers(text: str) -> Iterable[Decimal]:
    for m in _NUMBER.finditer(text):
        raw = m.group().rstrip(",").replace(",", "")
        try:
            yield Decimal(raw)
        except InvalidOperation:
            continue


def _walk(value: Any) -> Iterable[Decimal]:
    if isinstance(value, bool):
        return
    if isinstance(value, (int, float)):
        yield Decimal(str(value))
    elif isinstance(value, str):
        yield from _numbers(value)
    elif isinstance(value, dict):
        for k, v in value.items():
            yield from _numbers(str(k))
            yield from _walk(v)
    elif isinstance(value, list):
        for v in value:
            yield from _walk(v)


def _rounded(value: Decimal, places: int) -> Decimal:
    return value.quantize(Decimal(1).scaleb(-places), rounding=ROUND_HALF_UP).normalize()


def allowed_numbers(facts: dict) -> set[Decimal]:
    """근거 데이터에 나온 숫자와, 같은 값을 다르게 쓴 표기(만원·억 단위, 반올림)."""
    allowed = set(_ALWAYS_ALLOWED)
    for n in _walk(facts):
        variants = {n, n.normalize(), _rounded(n, 0), _rounded(n, 1)}
        if n >= 10_000:
            man = n / Decimal(10_000)
            variants |= {man.normalize(), _rounded(man, 0), _rounded(man, 1)}
        if n >= 100_000_000:
            eok = n / Decimal(100_000_000)
            variants |= {eok.normalize(), _rounded(eok, 1), _rounded(eok, 2)}
        allowed |= {v.normalize() for v in variants}
    return allowed


def validate(explanation: str, checkpoints: list[str], facts: dict) -> list[dict]:
    """규칙 기반 출력 검증. 문제가 없으면 빈 목록."""
    issues: list[dict] = []
    text = explanation.strip()
    if not (MIN_LENGTH <= len(text) <= MAX_LENGTH):
        issues.append({"code": "LENGTH", "message": f"설명 길이가 {len(text)}자입니다 ({MIN_LENGTH}~{MAX_LENGTH}자)."})
    if any(len(c) > MAX_CHECKPOINT_LENGTH for c in checkpoints):
        issues.append({"code": "LENGTH", "message": "확인 사항이 너무 깁니다."})
    whole = " ".join([text, *checkpoints])
    allowed = allowed_numbers(facts)
    unsupported = sorted({str(n) for n in _numbers(whole) if n.normalize() not in allowed})
    if unsupported:
        issues.append({"code": "UNSUPPORTED_NUMBER", "message": f"근거 데이터에 없는 숫자: {', '.join(unsupported[:5])}"})
    verdict = sorted(set(_VERDICT.findall(whole)))
    if verdict:
        issues.append({"code": "VERDICT_LANGUAGE", "message": f"단정적인 판정 표현: {', '.join(verdict)}"})
    if _EXCEEDS.search(whole) and not _EXCEEDS.search(json.dumps(facts, ensure_ascii=False)):
        issues.append({"code": "COMPARISON_CONFLICT", "message": "근거에 없는 \"기준 초과\" 주장이 있습니다."})
    conflicts = direction_conflicts(text, facts)
    if conflicts:
        issues.append({"code": "DIRECTION_CONFLICT", "message": f"근거와 반대인 증감 방향: {', '.join(conflicts[:3])}"})
    if _FOREIGN.search(whole) or not _HANGUL.search(text):
        issues.append({"code": "LANGUAGE", "message": "한국어가 아니거나 한자·외국 문자가 섞였습니다."})
    return issues


# 손익 합계 이름. "상품매출"처럼 다른 낱말 안에 든 것은 제외한다
_TOTAL_LABEL = re.compile(r"(?<![가-힣])(매출|비용|영업이익)")
# 문장 끝. "66.7%"처럼 숫자 사이의 점은 끊지 않는다
_SENTENCE_END = re.compile(r"(?<!\d)\.|\.(?!\d)|[!?\n]")


def _direction(text: Any) -> str | None:
    s = str(text or "")
    if "증가" in s:
        return "증가"
    if "감소" in s:
        return "감소"
    return None


def direction_conflicts(text: str, facts: dict) -> list[str]:
    """월마감 설명에서 매출·비용·영업이익의 증감 방향을 근거와 반대로 쓴 곳.

    문장을 합계 이름이 나올 때마다 끊어, 한 조각 안에 비교 기준(전월 또는 전년)이 하나만 있고
    근거와 반대 방향 낱말만 있으면 충돌로 본다. 애매하면 통과시킨다.
    """
    summary = facts.get("손익 요약")
    if not isinstance(summary, list):
        return []
    expected: dict[str, dict[str, str | None]] = {}
    for item in summary:
        if isinstance(item, dict) and item.get("항목"):
            expected[str(item["항목"])] = {"전월": _direction(item.get("전월 대비")),
                                         "전년": _direction(item.get("전년 동월 대비"))}
    conflicts: list[str] = []
    for sentence in _SENTENCE_END.split(text):
        labels = list(_TOTAL_LABEL.finditer(sentence))
        for i, m in enumerate(labels):
            end = labels[i + 1].start() if i + 1 < len(labels) else len(sentence)
            chunk = sentence[m.start():end]
            has_prev_month = "전월" in chunk
            if has_prev_month == ("전년" in chunk):
                continue
            basis = "전월" if has_prev_month else "전년"
            want = expected.get(m.group(1), {}).get(basis)
            if want is None:
                continue
            opposite = "감소" if want == "증가" else "증가"
            if opposite in chunk and want not in chunk:
                conflicts.append(f"{m.group(1)} {basis} 대비 {opposite}")
    return conflicts


# ------------------------------------------------------------------ 템플릿 설명


def _get(d: Any, *keys: str, default: Any = None) -> Any:
    for k in keys:
        if not isinstance(d, dict) or k not in d:
            return default
        d = d[k]
    return d


def fallback(target_type: str, facts: dict) -> tuple[str, list[str]]:
    """규칙 근거만으로 만드는 설명. 근거 데이터의 문장과 숫자를 그대로 옮긴다."""
    if target_type == "ANOMALY_SCAN":
        levels = _get(facts, "위험 수준별 건수", default={}) or {}
        level_text = ", ".join(f"{k} {v}건" for k, v in levels.items())
        text = (
            f"{_get(facts, '분석 기간', default='분석 기간')} 동안 출금 {_get(facts, '분석한 출금 건수', default=0)}건을 분석해 "
            f"{_get(facts, '탐지 건수(40점 이상)', default=0)}건을 이상거래 후보로 탐지했습니다"
            + (f" ({level_text})." if level_text else ".")
        )
        rules = _get(facts, "규칙별 탐지 건수", default=[]) or []
        if rules:
            text += " 가장 많이 걸린 근거는 " + ", ".join(f"{r.get('규칙')}({r.get('건수')}건)" for r in rules[:3]) + "입니다."
        top = _get(facts, "위험도 상위 거래", default=[]) or []
        checkpoints = [f"{t.get('거래처')} {t.get('출금액')} 출금 확인 (위험도 {t.get('위험도 점수')}점)" for t in top[:3]]
        return text, checkpoints

    if target_type == "CLOSING_MONTH":
        return _closing_fallback(facts)

    tx = _get(facts, "거래", default={}) or {}
    risk = _get(facts, "위험도", default={}) or {}
    rules = _get(facts, "규칙 근거", default=[]) or []
    text = (
        f"{tx.get('거래처', '거래처')}에 대한 {tx.get('출금액', '')} 출금({tx.get('거래일시', '')})이 "
        f"위험도 {risk.get('위험도 점수', '')}점({risk.get('위험 수준', '')})으로 탐지되었습니다."
    )
    reasons = [str(r.get("사유")) for r in rules[:2] if r.get("사유")]
    if reasons:
        text += " " + " ".join(reasons)
    ml_score = _get(facts, "ML 근거", "이상 점수")
    if ml_score:
        text += f" ML 이상 점수는 {ml_score}입니다."
    checkpoints = [CHECKPOINT_BY_RULE[r["코드"]] for r in rules if r.get("코드") in CHECKPOINT_BY_RULE][:3]
    return text, checkpoints


def _closing_fallback(facts: dict) -> tuple[str, list[str]]:
    """월마감 템플릿: 손익 요약 → 주요 변동 상위 3개 → 이상거래 → 체크리스트. 근거 문장을 그대로 옮긴다."""
    month = _get(facts, "기준월", default="당월")
    parts = []
    for item in _get(facts, "손익 요약", default=[]) or []:
        # 매출·비용·영업이익 모두 받침으로 끝난다
        parts.append(f"{item.get('항목')}은 {item.get('당월')}(전월 대비 {item.get('전월 대비')})")
    sentences = [f"{month} " + ", ".join(parts) + "입니다." if parts else f"{month} 손익 집계입니다."]

    changes = _get(facts, "주요 변동", default=[]) or []
    if changes:
        sentences.append("주요 변동은 " + "; ".join(str(c.get("내용")) for c in changes[:3]) + "입니다.")
    else:
        sentences.append("탐지 기준에 해당하는 주요 변동은 없습니다.")

    open_count = _get(facts, "이상거래 현황", "검토 대기", default=0) or 0
    if open_count:
        high = _get(facts, "이상거래 현황", "검토 대기 중 고위험(높음 이상)", default=0) or 0
        sentences.append(f"검토 대기 이상거래가 {open_count}건(고위험 {high}건) 있습니다.")

    progress = _get(facts, "마감 체크리스트", "진행률", default="")
    remaining = _get(facts, "마감 체크리스트", "남은 항목", default=[]) or []
    if remaining:
        sentences.append(f"마감 체크리스트는 {progress}이며 남은 항목은 {', '.join(remaining)}입니다.")
    elif progress:
        sentences.append(f"마감 체크리스트는 {progress}입니다.")

    checkpoints = [f"{r} 처리" for r in remaining[:2]]
    for c in changes:
        if len(checkpoints) >= 3:
            break
        item = f"{c.get('항목')} 변동 사유 확인"
        if item not in checkpoints:
            checkpoints.append(item)
    return " ".join(sentences), checkpoints[:3]


# ------------------------------------------------------------------ 생성


def build_user_prompt(facts: dict) -> str:
    return "근거 데이터:\n" + json.dumps(facts, ensure_ascii=False, indent=2)


def _parse(content: str) -> tuple[str, list[str]]:
    data = json.loads(content)
    explanation = data["explanation"]
    checkpoints = data.get("checkpoints") or []
    if not isinstance(explanation, str) or not isinstance(checkpoints, list):
        raise ValueError("형식 오류")
    return explanation.strip(), [str(c).strip() for c in checkpoints if str(c).strip()][:3]


def explain(target_type: str, facts: dict, provider: LlmProvider) -> dict:
    """설명 한 건. 결과는 Spring 콜백 형식 {status, text, checkpoints, meta}.

    LLM이 실패하거나 검증을 통과하지 못하면 FALLBACK(템플릿)이고, 그 이유와 거부된 출력은 meta에 남긴다.
    """
    if target_type not in TARGET_TYPES:
        raise ValueError(f"지원하지 않는 설명 대상: {target_type}")
    meta: dict[str, Any] = {
        "provider": provider.name,
        "model": provider.model,
        "promptVersion": PROMPT_VERSION[target_type],
    }
    try:
        reply = provider.chat(SYSTEM[target_type], build_user_prompt(facts), SCHEMA)
        meta["durationMs"] = reply.duration_ms
        try:
            text, checkpoints = _parse(reply.content)
        except (ValueError, KeyError, TypeError):
            issues = [{"code": "INVALID_OUTPUT", "message": "LLM 출력이 JSON 형식이 아닙니다."}]
            text, checkpoints = "", []
        else:
            issues = validate(text, checkpoints, facts)
        meta["validation"] = issues
        if not issues:
            return {"status": "GENERATED", "text": text, "checkpoints": checkpoints, "meta": meta}
        meta["rejectedText"] = (text or reply.content)[:2000]
    except LlmError as e:
        meta["llmError"] = str(e)[:500]
        meta["validation"] = []
    fb_text, fb_checkpoints = fallback(target_type, facts)
    return {"status": "FALLBACK", "text": fb_text, "checkpoints": fb_checkpoints, "meta": meta}
