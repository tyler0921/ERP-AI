# Phase 5 — Local LLM 설계

## 1. 목표

Ollama 기반 로컬 LLM으로 **이상거래가 왜 탐지되었는지**와 **분석 결과 요약**을 사람이 읽기 쉬운 문장으로 설명한다.

- LLM은 **설명만** 한다. 이상 여부 판단·Risk Score·금액 계산·검증·승인은 하지 않는다. (Phase 4의 규칙·알고리즘과 사람이 한다)
- LLM에는 원본 데이터가 아니라 Spring이 이미 계산해 둔 **근거 데이터(facts)** 만 전달한다. 전달한 facts는 그대로 저장해 무엇을 보고 설명했는지 확인할 수 있다.
- LLM이 근거에 없는 숫자를 만들거나 단정 표현을 쓰면 **규칙 기반 검증**이 걸러내고, 규칙 근거로 만든 **템플릿 설명**으로 대신한다.
- 설명은 사람이 수정할 수 있고, 수정은 Audit Log에 남는다.

## 2. 아키텍처

```
Spring  분석(scan) 커밋 후 ──LPUSH──▶ Valkey  ledgerguard:llm:jobs
         (facts + factsHash + attempt)        │ BRPOP (OCR 큐와 별도 스레드)
                                              ▼
                                    FastAPI LLM worker
                                      프롬프트 → LlmProvider(Ollama) → JSON 출력
                                      → 검증(숫자·단정 표현·길이) → 통과 못 하면 템플릿
                                              │
Spring ◀── POST /internal/v1/explanations/{id}/result (X-Internal-Token)
         현재 attempt의 결과만 반영 (멱등)
```

| 책임 | 위치 |
|---|---|
| 근거 데이터(facts) 구성·해시, 요청 시점 결정, 저장, 수정·재생성, 시간 초과, Audit | Spring (`explanation` 패키지) |
| 프롬프트, LLM 호출, 출력 검증, 템플릿 대체 설명 | FastAPI (`app/llm/`) |
| 모델 실행 | Ollama (`ollama/ollama:0.34.4`, 기본 모델 `qwen2.5:7b-instruct`) |

- **LLM Provider 인터페이스**: `LlmProvider.chat(system, user, schema)`. 구현은 `OllamaProvider` 하나이고, 모델은 `LLM_MODEL` 환경 변수로 바꾼다. 특정 모델에 결합하지 않는다.
- **비동기**: 로컬 LLM은 건당 수 초가 걸려 분석 응답을 막지 않도록 큐로 처리한다. 위험도 높은 순서로 넣는다.
- **GPU**: 기본 `docker-compose.yml`은 GPU 없이 동작한다(CPU 추론, 느림). NVIDIA GPU가 있으면 `docker-compose.gpu.yml`을 겹쳐 실행한다.
- 첫 실행 때 worker가 모델이 없으면 Ollama에 내려받기를 요청한다(볼륨에 캐시).

## 3. 설명 대상과 요청 시점

| 대상 (`targetType`) | 근거 데이터 | 요청 시점 |
|---|---|---|
| `ANOMALY_RESULT` | 거래(일시·거래처·금액·적요·계정과목), 위험도(점수·수준·규칙/ML 점수), 규칙 근거(제목·배점·사유), ML 근거(상태·점수·벗어난 특성) | 분석 후 OPEN인 결과 중 **근거가 바뀐 것**(factsHash 비교). 재분석해도 근거가 같으면 다시 만들지 않는다 |
| `ANOMALY_SCAN` | 기간, 분석·탐지 건수, 수준별 건수, 규칙별 건수, ML 상태, 위험도 상위 5건 | 분석마다 |

- 사람이 수정한 설명(`EDITED`)은 재분석이 덮어쓰지 않는다. 근거가 바뀌면 `stale` 표시만 하고, 사람이 "다시 생성"할 수 있다.
- 금액은 Spring이 `4,950,000원`처럼 표시 형식으로 넣는다. LLM이 숫자를 그대로 옮겨 쓰게 하기 위해서다.

## 4. 상태

```
(요청) ─▶ PENDING ──LLM 통과──▶ GENERATED
             │      └─검증 실패·LLM 오류──▶ FALLBACK (템플릿 설명 + 사유)
             └─큐 장애·시간 초과(30분)──▶ FAILED ──다시 생성──▶ PENDING
GENERATED / FALLBACK / FAILED ──사람이 수정──▶ EDITED
```

- 콜백은 현재 `attempt`의 결과만 반영한다. 시간 초과로 FAILED가 된 뒤 늦게 도착한 같은 attempt의 결과는 반영한다.

## 5. 출력 검증 (FastAPI, 규칙 기반)

| 코드 | 규칙 |
|---|---|
| `UNSUPPORTED_NUMBER` | 설명에 나온 숫자가 근거 데이터의 숫자(표시 형식·만원/억 단위·백분율·반올림 포함)에 없음 |
| `VERDICT_LANGUAGE` | "사기", "횡령", "불법", "범죄" 같은 단정 표현 |
| `LENGTH` | 설명이 비었거나 너무 김 |
| `INVALID_OUTPUT` | JSON 형식이 아님 |

하나라도 걸리면 `FALLBACK`이 되고, 거부된 LLM 출력은 `meta.rejectedText`에 남긴다.

## 6. Entity — `AiExplanation`

`(targetType, targetId)` 유일. `status`, `attempt`, `factsHash`, `facts` JSONB(LLM에 준 입력), `text`, `checkpoints` JSONB(담당자 확인 사항), `meta` JSONB(provider·model·promptVersion·durationMs·validation·rejectedText·stale), 요청·완료 시각, 수정자·수정 시각.

## 7. API

| Method | Path | 설명 |
|---|---|---|
| GET | `/companies/{cid}/anomalies/{id}` | 응답에 `explanation` 포함 |
| PUT | `/companies/{cid}/anomalies/{id}/explanation` `{text, checkpoints}` | 설명 수정 → EDITED (Audit) |
| POST | `/companies/{cid}/anomalies/{id}/explanation/regenerate` | 다시 생성 |
| GET | `/companies/{cid}/anomalies/summary` | `lastScan.explanation`에 분석 요약 |
| POST | `/companies/{cid}/anomalies/scans/{scanId}/summary/regenerate` | 요약 다시 생성 |
| POST | `/internal/v1/explanations/{id}/result` | LLM worker 콜백 (내부 토큰) |

FastAPI: `POST /v1/explain` (facts → 설명, 디버그·데모용, 저장 안 함), `/health`에 LLM 모델·연결 상태.

권한: 조회는 모든 멤버, 수정·다시 생성은 ADMIN·ACCOUNTANT.
