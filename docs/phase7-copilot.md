# Phase 7 — Accounting AI Q&A (AI Copilot) 설계

## 1. 목표

사용자가 자기 회사의 회계 데이터를 자연어로 묻고 답을 받는다.

```
질문 → (LLM) Query Planning: 미리 정의된 Query Tool 하나와 파라미터 선택
     → (Spring) 계획 검증 → Query Tool 실행(읽기 전용, 회사 조건 고정) → 결과 계산
     → (LLM) 계산된 결과를 문장으로 설명 → 출력 검증 (실패 시 Spring이 만든 요약 문장)
```

- **LLM은 SQL을 만들지 않는다.** 고를 수 있는 것은 아래 Query Tool 5개와 그 파라미터(enum·이름·숫자)뿐이고, Spring이 다시 검증한다. 회사는 URL 경로에서만 정해진다.
- **기간 계산도 LLM이 하지 않는다.** LLM은 "이번 달", "3분기" 같은 기간을 기호(`THIS_MONTH`, `QUARTER` + 3)로만 고르고, 실제 날짜 범위와 비교 기간은 Spring이 계산한다.
- 금액·증감·순위는 모두 Spring이 계산한다. LLM은 그 결과를 문장으로 옮긴다.
- LLM이 실패하거나 계획이 틀리면 **규칙 기반 Planner**(키워드)로 대체한다. 답변이 실패하면 Spring이 만든 요약 문장(템플릿)을 쓴다.
- 해석(계획)과 답변은 사람이 고칠 수 있고, 고친 내역은 Audit Log에 남는다.

## 2. Query Tool (Spring, 읽기 전용)

| Tool | 파라미터 | 결과 |
|---|---|---|
| `PROFIT_SUMMARY` | period, compare | 매출·비용·영업이익 (기간·비교 기간·증감), 계정과목별 금액 상위 |
| `ACCOUNT_SUMMARY` | account, period, compare | 계정과목 하나의 기간 합계·비교·증감, 월별 금액, (비용 계정이면) 거래처 상위 |
| `VENDOR_RANKING` | period, compare, order(`AMOUNT`·`INCREASE`·`DECREASE`), account?, limit | 거래처별 비용 순위 (금액 또는 증감액 기준) |
| `ANOMALY_LIST` | period, minLevel, status(`OPEN`·`ALL`), limit | 이상거래 목록 (위험도 높은 순) |
| `CLOSING_STATUS` | period(한 달) | 월마감 진행률·남은 체크리스트·확정 상태 |
| `UNSUPPORTED` | reason | 조회하지 않음. 지원하지 않는 이유와 가능한 질문 예시 |

- 집계 기준은 Phase 6과 같다(취소 전표 제외, 수익 = 대변−차변, 비용 = 차변−대변, 거래처 = 거래 → 승인 증빙 → 확정 대사 은행 거래).
- 부서 데이터는 데이터 모델에 없어 부서 질문은 `UNSUPPORTED`다.
- `limit`은 1~20, 기본 5. 기간은 최대 24개월, 미래만으로 된 기간은 거부한다.

### 기간 (period)

| type | 의미 |
|---|---|
| `THIS_MONTH` / `LAST_MONTH` | 이번 달 / 지난 달 (회사 기준 시간대 Asia/Seoul) |
| `MONTH` + year, month | 특정 월 (year 없으면 올해) |
| `THIS_QUARTER` / `LAST_QUARTER` / `QUARTER` + year, quarter | 분기 |
| `THIS_YEAR` / `LAST_YEAR` / `YEAR` + year | 연도 (올해는 이번 달까지) |

기간이 없는 질문은 `LAST_MONTH`(마감 대상 월)로 본다. 비교(compare): `PREV_PERIOD`(바로 앞 같은 길이) 또는 `PREV_YEAR`(전년 같은 기간), 기본 `PREV_PERIOD`.

## 3. Planner (ai-service)

- LLM Planner: 오늘 날짜, 회사의 수익·비용 계정과목 이름 목록, Tool 설명을 주고 JSON Schema(구조화 출력)로 계획 하나를 받는다. 질문은 데이터로만 다룬다.
- 형식 검증(Tool·기간 enum, 숫자 범위, 계정과목이 목록에 있는지)에 실패하면 규칙 Planner로 대체하고, LLM 계획과 문제를 meta에 남긴다.
- **LLM 계획 보정** (실제 qwen2.5 7B 출력에서 본 오류): 질문에 기간 표현이 있으면 기간은 규칙 파서 결과를 쓴다("9월"을 `LAST_MONTH`로 바꾼 적이 있음). "작년 9월 대비 올해 9월"처럼 비교 표현이 있으면 그 뒤쪽이 조회 기간이다. 질문에 계정과목이 나오는데 손익 전체(`PROFIT_SUMMARY`)를 고르면 그 계정 조회로 바꾼다("소모품비"를 무시한 적이 있음). 바로잡은 내역은 `planMeta.corrections`에 남는다.
- 규칙 Planner: 기간 표현(이번 달, 지난달, N월, YYYY년 N월, N분기, 올해, 작년), 의도 키워드(이상·위험 → `ANOMALY_LIST`, 거래처 → `VENDOR_RANKING`, 마감 → `CLOSING_STATUS`, 매출·이익·손익 → `PROFIT_SUMMARY`, 계정과목 이름·별칭 → `ACCOUNT_SUMMARY`, 부서 → `UNSUPPORTED`).
- 계정과목 별칭(규칙 Planner·Spring 공통): 광고비 → 광고선전비, 인건비·월급 → 급여, 월세 → 임차료, 수수료 → 지급수수료, 택배·배송비 → 운반비, 식대·회식비 → 복리후생비, 사무용품·문구 → 소모품비, 외주 → 외주비.

## 4. Entity — `CopilotQuestion`

| 필드 | 설명 |
|---|---|
| company_id, asked_by | 회사, 질문한 사용자 |
| question | 질문 (최대 500자) |
| status | `PLANNING` → `ANSWERED` / `UNSUPPORTED` / `FAILED` |
| attempt | 계획 시도 번호 (콜백 멱등) |
| plan, plan_source | 검증된 계획, `LLM` / `RULE` / `USER` |
| plan_meta | 모델·소요 시간·LLM이 낸 원래 계획·검증 문제 |
| result | Query Tool 결과 (표시용 열·행·요약 문장) |
| error | 실패 사유 |

답변은 Phase 5의 `ai_explanations`(대상 `COPILOT_ANSWER`, 대상 ID = 질문 ID)를 그대로 쓴다.

## 5. API

| Method | Path | 설명 |
|---|---|---|
| POST | `/companies/{cid}/copilot/questions` `{question}` | 질문 (커밋 후 계획 작업을 LLM 큐에 넣음) |
| GET | `/companies/{cid}/copilot/questions` | 내 질문 목록 |
| GET | `/companies/{cid}/copilot/questions/{id}` | 질문·계획·결과·답변 |
| PUT | `…/{id}/plan` `{plan}` | 해석 수정 → 다시 조회·답변 (Audit) |
| POST | `…/{id}/retry` | 계획 실패 시 다시 시도 |
| PUT | `…/{id}/answer` | 답변 수정 (Audit) |
| POST | `…/{id}/answer/regenerate` | 답변 다시 생성 (Audit) |
| POST | `/internal/v1/copilot/questions/{id}/plan` | LLM worker 콜백 (`X-Internal-Token`) |

- 권한: 질문·조회는 모든 멤버(조회만 하므로 VIEWER 포함). 질문은 질문한 사람만 보고 고칠 수 있다(다른 사람 질문은 404).
- 한 사용자가 동시에 계획 중인 질문은 3개까지.
- 계획 콜백이 LLM 설명과 같은 시간(기본 30분, 같은 큐) 안에 오지 않으면 `FAILED`(다시 시도 가능).
- 해석을 고치면 근거가 바뀌므로, 생성 중이거나 사람이 수정한 답변도 새 시도로 대체한다(이전 시도의 결과는 무시).
- Audit: `COPILOT_ASK`, `COPILOT_PLAN_EDIT`, `COPILOT_ANSWER_EDIT`, `COPILOT_ANSWER_REGENERATE`.

## 6. 답변 (LLM)

- facts: 질문, 해석(Tool·기간·비교 기간 이름), 조회 결과(표시 형식 문자열), Spring이 만든 요약 문장.
- 프롬프트 `copilot-v1`: 질문에 직접 답하고 조회 결과에 없는 내용은 말하지 않는다. 결과가 비었으면 "해당 기간에 데이터가 없습니다"라고 쓴다.
- 검증: Phase 5·6 규칙(근거 밖 숫자, 단정 표현, 외국 문자) + 항목별 증감 방향(`증감 방향`과 반대인지) + Copilot 전용 규칙 두 가지 — 근거에 없는 "가장·최대" 같은 순위·극값 표현(계정 하나만 조회했는데 "가장 큰 증가액"이라고 덧붙인 적이 있음), 요약에 금액이 있는데 답변에 금액이 하나도 없는 경우(숫자 없이 추론만 늘어놓은 적이 있음). 실패하면 Spring의 요약 문장을 그대로 쓴다.
- `UNSUPPORTED`는 LLM 답변 없이 Spring이 이유와 예시 질문을 보여준다.

## 7. 화면

- `AI Copilot` 메뉴: 질문 입력, 예시 질문, 내 질문 목록, 선택한 질문의 해석(Tool·기간·비교, 수정 가능), 답변(수정·다시 생성), 조회 결과 표(이상거래는 상세 화면 링크).
