# Phase 4 — Anomaly Detection 설계

## 1. 목표

지출(출금) 거래마다 **규칙 엔진(Rule Engine)** 과 **IsolationForest(ML)** 를 함께 돌려 **Risk Score 0~100**과 **Risk Level**을 매긴다.
위험도가 일정 이상인 거래만 이상거래로 저장하고, 담당자가 근거를 보고 **이상 확정 / 정상 처리 / Risk Level 조정**을 한다.

- LLM은 사용하지 않는다. (자연어 설명은 Phase 5)
- 이상 여부를 판단하는 것은 규칙과 알고리즘이고, 최종 판정은 사람이 한다. 모든 결과에 규칙별 근거와 ML 근거(편차가 큰 특성)를 함께 저장한다.
- ML은 보조 신호다. ML만으로는 최대 40점(MEDIUM)까지만 올라가고, HIGH 이상은 규칙 근거가 있어야 한다.

## 2. 분석 대상

| 원천 (`sourceType`) | 대상 | 비고 |
|---|---|---|
| `BANK_TRANSACTION` | 은행 거래 중 출금(OUT) | 거래시간이 있어 심야 판단 가능. 계정과목·증빙은 **확정 대사된 전표**를 통해 찾는다 |
| `TRANSACTION` | 거래 중 출금(OUT), 취소(VOID) 제외 | 시간 정보 없음(주말만 판단). 계정과목·증빙은 **연결된 전표**를 통해 찾는다 |

- 입금(IN)은 Phase 4 대상이 아니다. (중복 결제·쪼개기 결제 등 규칙이 모두 지출 기준)
- 두 원천은 **각자의 모집단** 안에서만 비교한다. 같은 결제가 거래와 은행 거래로 함께 등록되는 경우가 많아, 섞으면 "중복 결제"로 잘못 잡힌다.
- 계정과목: 연결 전표에서 차변 금액이 가장 큰 라인의 계정과목. 증빙: 연결 전표에 붙은 **승인된** 증빙(Document).
- 거래처는 `TextSimilarity.normalize`(법인 표기·공백·기호 제거)로 같은 거래처를 묶는다.

## 3. 아키텍처

```
Browser ─▶ Next.js(BFF) ─▶ Spring Boot  POST /anomalies/scan {from, to}
                              │ ① 회사 행 잠금(같은 회사 분석 직렬화)
                              │ ② 이벤트 적재: 대상 기간 + 과거 180일(기준선) + 이후 7일(중복 창)
                              │ ③ AnomalyFeatureExtractor (순수 클래스) — 거래처 빈도·평균·간격 등
                              │ ④ AnomalyRuleEngine (순수 클래스)     — 규칙 적중과 근거
                              │ ⑤ ── POST /v1/anomaly/score ──▶ FastAPI  IsolationForest (원천별 모집단에 fit)
                              │                                   └ 점수 0~1 + 편차가 큰 특성(robust z)
                              │ ⑥ RiskScorer (순수 클래스)            — Rule + ML → Risk Score / Level
                              └ ⑦ 결과 저장(이상거래만), 검토된 결과 보존, 스캔 이력·Audit
```

| 책임 | 위치 |
|---|---|
| 데이터 적재, 특성 계산, 규칙, 점수 조합, 상태 결정, 검토, Audit | Spring |
| IsolationForest 학습·점수, ML 근거(특성 편차) | FastAPI (`app/anomaly/`) |

- ML 호출은 **동기 HTTP**다. 회사 단위 수천 건의 IsolationForest는 1초 안팎이라 OCR처럼 큐가 필요 없다.
- 모델은 `AnomalyModel` 인터페이스 뒤에 둔다(Python). 다른 이상탐지 모델로 바꾸거나 비교할 수 있다.
- IsolationForest는 비지도 학습이므로 저장된 모델이 없다. 요청마다 해당 회사·원천의 모집단으로 학습하고 `random_state`를 고정해 같은 데이터면 같은 점수가 나온다.

### AI 실패 처리

| 상황 | 처리 |
|---|---|
| AI 서비스 다운·시간 초과·오류 응답 | 규칙 점수만으로 Risk Score를 계산한다. 스캔·결과에 `mlStatus=UNAVAILABLE`과 사유를 남긴다 |
| 모집단이 30건 미만 | ML을 건너뛴다 (`SKIPPED`, "데이터 부족") |
| 회사 설정에서 ML 끔 | `DISABLED` |

규칙만으로도 분석이 완결되므로 AI 서비스가 없어도 기능은 동작한다.

## 4. 규칙 (Spring `AnomalyRuleEngine`)

| 코드 | 배점 | 규칙 |
|---|---|---|
| `DUPLICATE_PAYMENT` | 40 | 같은 거래처·같은 금액의 출금이 3일 이내에 또 있음 |
| `SPLIT_PAYMENT` | 45 | 같은 거래처에 같은 날 승인 기준 미만 출금이 2건 이상이고, 합계는 승인 기준 이상 |
| `NEAR_LIMIT` | 25 (반복 시 40) | 금액이 승인 기준의 90% 이상 ~ 기준 미만. 30일 이내에 이런 출금이 2건 이상이면 반복 |
| `AMOUNT_SPIKE` | 30 | 같은 거래처의 최근 180일 평균(과거 3건 이상)보다 5배 이상 |
| `NEW_VENDOR_HIGH` | 25 | 최근 180일 동안 거래가 없던 거래처에 100만원 이상 (회사 이력이 30일 이상 쌓인 뒤부터 적용) |
| `DUPLICATE_RECEIPT` | 40 | 연결된 증빙과 거래처 사업자번호·거래일·합계가 같은 다른 증빙이 있음 |
| `MISSING_EVIDENCE` | 20 | 승인된 증빙이 연결되지 않은 3만원 초과 출금 |
| `VENDOR_REPEAT` | 15 | 같은 거래처에 7일 동안 3회 이상 출금 |
| `OFF_HOURS` | 10 | 주말(토·일) 또는 심야(22시~6시, 시간 정보가 있을 때) |
| `ACCOUNT_SURGE` | 10 | 계정과목의 해당 월 합계가 직전 3개월 평균의 2배 이상이고 100만원 이상 증가 (직전 3개월 중 2개월 이상 사용 이력 필요) |

- 승인 기준 금액(기본 500만원), 직전 비율(90%), 증빙 기준(3만원), 신규 거래처 기준(100만원), 평균 대비 배수(5배), 심야 시간(22~6시), ML 사용 여부는 **회사 설정**(`anomaly_settings`, ADMIN)으로 바꿀 수 있다. 스캔에는 그때의 설정이 함께 저장된다.
- 각 규칙 적중은 `{code, title, weight, message, evidence}`로 저장한다. `evidence`에는 비교한 금액·날짜·관련 거래 ID 같은 판단 근거가 들어간다.
- 공휴일은 고려하지 않는다.

## 5. ML (FastAPI `app/anomaly/`)

Spring이 특성을 계산해 보내고, FastAPI는 변환·학습·점수만 한다.

| 특성 (spec 이름) | 계산 (Spring) | 모델 입력 변환 (Python) |
|---|---|---|
| `amount` | 금액 (문자열) | log1p |
| `transactionHour` | 거래 시각(시), 없으면 null | null → -1 |
| `weekday` | 1(월)~7(일) | 그대로 |
| `merchantFrequency` | 같은 거래처의 이전 90일 거래 수 | log1p |
| `averageAmount` | 같은 거래처의 이전 180일 평균 금액 (문자열, 없으면 null) | null → 금액, log1p |
| `amountRatioToAverage` | 금액 / 평균 (없으면 null) | null → 1, log |
| `monthlyTransactionCount` | 같은 거래처의 같은 달 거래 수 | log1p |
| `accountCategory` | 계정과목 코드 (없으면 null) | 모집단 내 빈도(빈도 인코딩) |
| `daysSinceLastTransaction` | 같은 거래처 직전 거래와의 간격(일), 없으면 null | null → 365, log1p |

- `IsolationForest(n_estimators=200, contamination="auto", random_state=42)`.
- 점수: 논문식 이상 점수 `s = -score_samples(x)` (0~1, 0.5 근처가 보통)를 `clamp((s - 0.5) / 0.25, 0, 1)`로 0~1 ML 점수로 바꾼다.
- ML 근거: 변환된 특성마다 모집단 중앙값·MAD 기준 robust z-score를 계산해 |z| ≥ 2인 특성을 큰 순서로 최대 3개 돌려준다. ("금액이 모집단 중앙값보다 크게 벗어남")

## 6. Risk Score

```
ruleScore = min(100, Σ 적중 규칙 배점)
mlPoints  = round(40 × mlScore)        (ML을 쓰지 못하면 0)
riskScore = min(100, ruleScore + mlPoints)
```

| Risk Level | 점수 |
|---|---|
| LOW | 0~39 |
| MEDIUM | 40~69 |
| HIGH | 70~84 |
| CRITICAL | 85~100 |

**MEDIUM(40점) 이상만 이상거래로 저장**한다. 사람이 Risk Level을 조정하면 `adjustedLevel`이 생기고, 화면·필터·집계는 `effectiveLevel = adjustedLevel ?? riskLevel`을 쓴다.

## 7. 상태와 재분석

```
(스캔: 40점 이상) ──▶ OPEN ──검토──▶ CONFIRMED (이상 확정) / DISMISSED (정상 처리)
                       │              └──다시 열기──▶ OPEN
                       └─(재스캔: 40점 미만 또는 원천 거래 취소)──▶ CLEARED ──(다시 40점 이상)──▶ OPEN
```

- 검토할 때는 의견이 필수이고, Risk Level을 함께 조정할 수 있다. 검토·다시 열기·설정 변경은 Audit Log에 before/after로 남는다.
- **재스캔은 사람의 판정을 덮어쓰지 않는다.** CONFIRMED·DISMISSED 결과는 그대로 둔다. 단, 검토 이후 **새로운 규칙이 적중**하면(새 근거) 점수를 갱신하고 OPEN으로 다시 연다.
- 스캔 한 번이 수정하는 결과는 많을 수 있어 결과별 Audit 대신 스캔 이력(`anomaly_scans`)과 `ANOMALY_SCAN` Audit에 건수(신규·갱신·해제·다시 열림)를 남기고, 각 결과는 마지막 스캔 ID를 가진다.

## 8. Entity

- `AnomalySettings` (회사당 1행, 없으면 기본값): `approvalLimit`, `nearLimitRatio`, `evidenceMinAmount`, `newVendorMinAmount`, `spikeMultiplier`, `nightStartHour`, `nightEndHour`, `mlEnabled`
- `AnomalyScan`: 기간, 분석 건수, 저장·해제 건수, 수준별 건수, `mlStatus`·원천별 ML 정보(JSONB), 설정 스냅샷(JSONB), 소요 시간, 실행자
- `AnomalyResult`: `(company, sourceType, sourceId)` 유일. 거래 스냅샷(날짜·시각·거래처·금액), `riskScore`, `riskLevel`, `ruleScore`, `mlScore`, `ruleHits` JSONB, `features` JSONB, `mlDetail` JSONB, `context` JSONB(메모·계정과목·전표·증빙 ID), `status`, `adjustedLevel`, `effectiveLevel`(DB 생성 컬럼), 검토자·시각·의견, `scanId`

## 9. API

| Method | Path | 설명 |
|---|---|---|
| GET | `/companies/{cid}/anomaly-settings` | 탐지 기준 (멤버) |
| PUT | `/companies/{cid}/anomaly-settings` | 탐지 기준 변경 (ADMIN) |
| POST | `/companies/{cid}/anomalies/scan` `{from, to}` | 분석 실행 (최대 366일) |
| GET | `/companies/{cid}/anomalies/scans` | 최근 스캔 이력 |
| GET | `/companies/{cid}/anomalies?status&level&sourceType&from&to` | 이상거래 목록 (위험도 순) |
| GET | `/companies/{cid}/anomalies/summary` | 미검토 수준별 건수, 고위험 수, 마지막 스캔 |
| GET | `/companies/{cid}/anomalies/{id}` | 상세 (규칙 근거, ML 근거, 특성, 연결 전표·증빙) |
| POST | `/companies/{cid}/anomalies/{id}/review` `{decision, adjustedLevel?, note}` | 이상 확정(CONFIRMED) / 정상 처리(DISMISSED) |
| POST | `/companies/{cid}/anomalies/{id}/reopen` `{note}` | 검토 취소 → OPEN |

FastAPI: `POST /v1/anomaly/score` `{samples: [{id, features}], minSamples}` → `{status, model, scores: [{id, score, rawScore, topFeatures}]}` (저장하지 않음)

권한: 조회는 모든 멤버, 스캔·검토는 ADMIN·ACCOUNTANT, 설정 변경은 ADMIN.
