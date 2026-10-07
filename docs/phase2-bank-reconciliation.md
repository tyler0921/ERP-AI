# Phase 2 — Bank Reconciliation 설계

## 1. 목표

은행 거래내역(CSV)을 업로드하면, 규칙 기반 알고리즘이 회계 전표 중 매칭 후보를 찾아 **Match Score(0~100)** 와 **점수 근거**를 붙여 제안한다.
회계 담당자가 제안을 **승인/거절**하거나 직접 전표를 골라 매칭한다. 확정된 매칭만 대사 완료로 본다. (Human-in-the-loop)

- 범위: CSV 업로드·정규화·중복 방지, 매칭 후보 검색, Match Score, 1:1 자동 제안, 수동 매칭, 승인/거절/매칭 취소, 대사 현황
- 이후: 1:N / N:1 자동 후보 탐색 (데이터 모델은 지금부터 N:M을 표현할 수 있게 설계)

## 2. 핵심 개념 — "전표의 은행 금액"

전표 전체 금액이 아니라 **은행 계좌에 연결된 계정과목(예: 1020 보통예금)의 움직임**을 은행 거래와 비교한다.

| 은행 거래 | 비교 대상 전표 라인 |
|---|---|
| 출금(OUT) | 해당 계정과목의 **대변** 합계 |
| 입금(IN) | 해당 계정과목의 **차변** 합계 |

예: 출금 1,253,000 ↔ `외상매입금 1,250,000 / 지급수수료 3,000 / 보통예금(대) 1,253,000` 전표 → 은행 금액 1,253,000 → 1:1 일치

그래서 은행 계좌는 반드시 **자산 유형 계정과목 하나**에 연결된다 (회사 내 계정과목당 계좌 1개).

## 3. Entity

| Entity | 설명 |
|---|---|
| `BankAccount` | 회사의 은행 계좌. `glAccountId`(연결 계정과목), 계좌번호는 끝 4자리만 저장 |
| `BankStatementImport` | CSV 업로드 1회. 파일명, SHA-256, 인코딩, 전체/등록/중복 행 수 |
| `BankTransaction` | 정규화된 은행 거래 1건. **수정 불가**(원천 데이터). `dedupeKey`로 재업로드 중복 방지. 상태 `UNMATCHED → SUGGESTED → MATCHED` |
| `ReconciliationMatch` | 매칭 1건(헤더). `matchType`(ONE_TO_ONE …), `status`, `source`(AUTO/MANUAL), `score`, `scoreDetail`(JSONB 근거), 검토자·검토 의견 |
| `ReconciliationMatchItem` | 매칭에 참여하는 은행 거래 또는 전표 1개와 금액. `confirmed` 플래그 |

### Match 상태

```
SUGGESTED ──승인──▶ CONFIRMED ──매칭 취소──▶ CANCELLED
    └──────거절──▶ REJECTED   (같은 쌍은 다시 제안하지 않음)
수동 매칭 ─────────▶ CONFIRMED
```

## 4. DB 안전망

- `UNIQUE (bank_account_id, dedupe_key)` — 같은 거래 재업로드 방지
- `reconciliation_match_items`의 부분 유니크 인덱스 — 은행 거래·전표는 **확정 매칭에 최대 1번**만 참여
- 지연 제약 트리거 — 확정 매칭은 커밋 시점에 **은행 금액 합계 = 전표 금액 합계**
- 확정 매칭된 전표는 수정·취소할 수 없다 (서비스 검증, `JOURNAL_RECONCILED`). 매칭을 먼저 취소해야 한다.

## 5. Match Score (규칙 기반, 0~100)

| 항목 | 배점 | 규칙 |
|---|---|---|
| 금액 | 40 | 은행 금액 = 전표 은행 금액이면 40, 아니면 0 |
| 날짜 | 20 | 차이 0일 20 · 1일 16 · 2일 12 · 3일 8 · 4~7일 4 · 그 이상 0 |
| 거래처 | 20 | 정규화(`(주)`, `주식회사`, 공백·기호 제거) 후 포함 관계면 1.0, 아니면 문자 bigram Dice 유사도. × 20 |
| 메모 | 10 | 은행 메모 ↔ 전표 적요·연결 거래 메모. 거래처와 같은 방식(포함 관계 우선, 아니면 bigram Dice) × 10 |
| 과거 패턴 | 10 | 같은 거래처의 과거 확정 매칭 전표와 상대 계정과목이 겹치면: 1회 5, 2회 이상 10 |

| 총점 | 등급 | 처리 |
|---|---|---|
| 90 이상 | HIGH | 높은 매칭 가능성 — 제안 (승인은 사람) |
| 70~89 | REVIEW | 담당자 검토 필요 — 제안 |
| 70 미만 | — | 미매칭 (후보 조회로 수동 매칭 가능) |

후보 범위: 같은 회사, VOID 아닌 전표, 은행 계좌 계정과목 라인이 있고 방향이 맞는 전표, 아직 확정 매칭되지 않은 전표, 날짜 ±30일.
자동 제안은 점수 높은 쌍부터 **탐욕적(greedy) 1:1 배정** — 한 전표가 두 은행 거래에 동시에 제안되지 않는다.
LLM은 사용하지 않는다. 점수의 근거(`scoreDetail`)는 이후 Phase 5에서 LLM 설명의 입력이 된다.

## 6. CSV 업로드 규칙

- 인코딩: UTF-8(BOM 허용) 우선, 실패 시 CP949(국내 은행 기본) 자동 감지
- 헤더 별칭: 거래일자/거래일/날짜/date, 거래시간/time, 출금액/출금/찾으신금액, 입금액/입금/맡기신금액, 금액/amount + 구분, 잔액/balance, 거래처/적요/내용/받는분, 메모/비고/memo
- 날짜: `yyyy-MM-dd`, `yyyy.MM.dd`, `yyyy/MM/dd`, `yyyyMMdd` (+ 선택적 `HH:mm[:ss]`)
- 금액: 쉼표·`원` 제거, 소수점 둘째 자리까지
- **전부 성공 아니면 전부 실패**: 잘못된 행이 하나라도 있으면 행 번호와 사유를 돌려주고 아무것도 저장하지 않는다
- 이미 등록된 거래(같은 dedupeKey)는 건너뛰고 `duplicateRows`로 알려준다
- 제한: 2MB, 5,000행

## 7. API (`/api/v1/companies/{cid}`)

| Method | Path | 권한 | 설명 |
|---|---|---|---|
| GET/POST | `/bank-accounts` | 조회 전원 / 등록 쓰기 | 은행 계좌 |
| PATCH | `/bank-accounts/{id}` | 쓰기 | 이름·활성 여부 |
| POST | `/bank-accounts/{id}/imports` (multipart `file`) | 쓰기 | CSV 업로드 |
| GET | `/bank-accounts/{id}/imports` | 조회 | 업로드 이력 |
| GET | `/bank-transactions?bankAccountId&status&from&to` | 조회 | 은행 거래 + 현재 매칭 요약 |
| GET | `/bank-transactions/{id}/candidates` | 조회 | 점수순 후보 전표 (최대 10) |
| POST | `/reconciliation/run` `{bankAccountId}` | 쓰기 | 자동 대사 실행 → 제안 생성 |
| POST | `/reconciliation/matches` `{bankTransactionId, journalEntryId, note}` | 쓰기 | 수동 매칭(즉시 확정) |
| POST | `/reconciliation/matches/{id}/confirm` `{note}` | 쓰기 | 제안 승인 |
| POST | `/reconciliation/matches/{id}/reject` `{note}` | 쓰기 | 제안 거절 |
| POST | `/reconciliation/matches/{id}/cancel` `{reason}` | 쓰기 | 확정 매칭 취소 |
| GET | `/reconciliation/summary?bankAccountId` | 조회 | 전체·매칭·제안·미매칭 수, 대사율, 자동대사 성공률 |

- **대사율** = MATCHED 은행 거래 / 전체 은행 거래
- **자동대사 성공률** = 승인된 자동 제안 / (승인 + 거절된 자동 제안)

모든 쓰기 작업은 같은 트랜잭션에서 Audit Log를 남긴다 (`BANK_ACCOUNT_CREATE`, `BANK_IMPORT`, `RECON_RUN`, `RECON_CONFIRM`, `RECON_REJECT`, `RECON_MANUAL_MATCH`, `RECON_CANCEL`).
