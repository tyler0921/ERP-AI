# Phase 3 — Document AI 설계

## 1. 목표

영수증·세금계산서(이미지/PDF)를 업로드하면 **PaddleOCR**로 텍스트를 읽고, **규칙 기반 추출기**가 거래처·금액·날짜 등을 필드로 만든다.
Spring이 **규칙으로 검증**(공급가액 + 부가세 = 합계, 사업자등록번호 체크섬 등)하고 **계정과목을 추천**한다.
담당자가 필드를 확인·수정하고 승인하면, 그 내용으로 **전표를 생성**해 연결한다.

- LLM은 사용하지 않는다. (설명은 Phase 5)
- 모든 AI 결과는 필드별 confidence와 근거(OCR 원문, 추출 규칙)를 함께 저장하고, 사람이 수정할 수 있으며, 수정은 Audit Log에 남는다.

## 2. 아키텍처

```
Browser ──▶ Next.js(BFF) ──▶ Spring Boot ──▶ SeaweedFS (파일 저장, filer HTTP API)
                                  │
                                  ├─ LPUSH ──▶ Valkey  ledgerguard:ocr:jobs
                                  │                │ BRPOP
                                  │                ▼
                                  │          FastAPI AI worker ──GET 파일──▶ SeaweedFS
                                  │            PaddleOCR → 필드 추출 → 계정과목 규칙
                                  │                │
                                  ◀── POST /internal/v1/documents/{id}/ai-result (X-Internal-Token)
                               검증 · 추천 확정 · 상태 결정 · Audit
```

| 책임 | 위치 |
|---|---|
| 파일 검증(형식·크기·매직바이트), 저장, 접근 제어 | Spring |
| 비동기 작업 큐 | Valkey (List) |
| OCR, 필드 추출, 키워드 기반 계정과목 후보 | FastAPI |
| 검증 규칙, 과거 이력 기반 추천, 상태 결정, 승인, 전표 생성, Audit | Spring |

### AI 실패 처리

| 상황 | 처리 |
|---|---|
| 업로드 시 Valkey 연결 실패 | 파일·문서는 저장, 상태 `FAILED`("작업 큐 연결 실패") → **재시도** 가능 |
| OCR/추출 예외 | worker가 실패 결과를 콜백 → `FAILED` + 오류 메시지 |
| AI 서버 다운 | 작업은 큐에 남아 서버가 다시 뜨면 처리 |
| 응답 없음 | 스케줄러가 `PENDING` 15분 초과 문서를 `FAILED`("시간 초과")로 → 재시도 |
| 늦게 도착한 이전 시도의 결과 | `attempt` 번호가 다르면 무시 (멱등) |
| OCR 실패 | 사람이 필드를 직접 입력해 진행할 수 있다 |

## 3. 상태

```
PENDING ──AI 결과──▶ AI_REVIEWED      (검증 오류·경고 없음, confidence ≥ 0.8)
   │            └──▶ REVIEW_REQUIRED  (오류/경고 있음 또는 confidence 낮음)
   └──실패──▶ FAILED ──재시도──▶ PENDING
AI_REVIEWED / REVIEW_REQUIRED / FAILED ──필드 수정──▶ (재검증 후 AI_REVIEWED 또는 REVIEW_REQUIRED)
AI_REVIEWED / REVIEW_REQUIRED ──승인(검증 오류 없음, 계정과목 지정)──▶ APPROVED ──전표 생성──▶ (journal_entry_id 연결)
                               └──거절──▶ REJECTED
```

## 4. Entity — `Document`

- 파일: `docType`(RECEIPT / TAX_INVOICE), `fileKey`, `fileName`, `contentType`, `fileSize`, `fileSha256`
- 현재 필드 값(사람이 수정 가능): `vendorName`, `vendorBusinessNo`, `buyerName`, `buyerBusinessNo`, `issueDate`, `supplyAmount`, `vatAmount`, `totalAmount`, `paymentMethod`, `itemsSummary`
- AI 원본(수정 불가): `aiResult` JSONB — OCR 줄·점수, 필드별 값·confidence·근거, 엔진/모델, 처리 시간
- `aiConfidence`, `validation` JSONB(검증 결과 목록), `recommendation` JSONB(추천 후보와 사유)
- `accountId`(승인된 계정과목), `journalEntryId`, 검토자·시각·의견, `attempt`, `errorMessage`
- 같은 회사에 같은 파일(SHA-256)은 다시 올릴 수 없다 (`DOCUMENT_DUPLICATE`)

## 5. 검증 규칙 (Spring, 규칙 기반)

| 코드 | 심각도 | 규칙 |
|---|---|---|
| `REQUIRED_MISSING` | ERROR | 필수 필드 누락 (공통: 거래처, 거래일, 합계 / 세금계산서: + 공급가액, 세액, 사업자번호 2개) |
| `SUM_MISMATCH` | ERROR | 공급가액 + 부가세 ≠ 합계 |
| `BIZ_NO_INVALID` | ERROR | 사업자등록번호 체크섬 불일치 |
| `VAT_RATE` | WARNING | 세액이 공급가액의 10%(원 단위 절사·반올림 ±1원)와 다름 |
| `FUTURE_DATE` | ERROR | 거래일이 오늘(KST) 이후 |
| `BUYER_MISMATCH` | WARNING | 세금계산서의 공급받는자 사업자번호가 우리 회사와 다름 |
| `POSSIBLE_DUPLICATE` | WARNING | 같은 회사에 같은 거래처 사업자번호·거래일·합계의 다른 문서가 있음 |
| `LOW_CONFIDENCE` | WARNING | 필드 confidence < 0.6 |

## 6. 계정과목 추천

1. **과거 이력(Spring):** 같은 거래처 사업자번호로 승인된 문서의 계정과목 → confidence 0.95
2. **키워드 규칙(FastAPI):** 거래처명·품목의 키워드 → 계정과목 코드 (예: 택배·퀵 → 5800 운반비, 광고·네이버 → 5400 광고선전비)
3. 회사 계정과목에 같은 코드(없으면 같은 이름)가 있어야 추천한다. 추천은 후보일 뿐이며 승인 시 사람이 확정한다.

## 7. 전표 생성 (승인된 문서)

| 라인 | 금액 |
|---|---|
| 차변: 승인된 계정과목 | 공급가액 (부가세가 없으면 합계) |
| 차변: 1350 부가세대급금 | 부가세 (있을 때) |
| 대변: 사용자가 고른 계정 (기본 2020 미지급금) | 합계 |

전표 생성은 기존 `JournalEntryService`를 그대로 사용하므로 차·대변 검증과 Audit가 동일하게 적용된다.

## 8. API

| Method | Path | 설명 |
|---|---|---|
| POST | `/companies/{cid}/documents` (multipart `file`, `docType`) | 업로드 → 저장 → 큐 등록 |
| GET | `/companies/{cid}/documents?status&docType` | 목록 |
| GET | `/companies/{cid}/documents/summary` | 상태별 건수 |
| GET | `/companies/{cid}/documents/{id}` | 상세 (필드, AI 결과, 검증, 추천) |
| GET | `/companies/{cid}/documents/{id}/file` | 원본 파일 |
| PUT | `/companies/{cid}/documents/{id}/fields` | 필드 수정 → 재검증 |
| POST | `/companies/{cid}/documents/{id}/approve` `{accountId, note}` | 승인 |
| POST | `/companies/{cid}/documents/{id}/reject` `{reason}` | 거절 |
| POST | `/companies/{cid}/documents/{id}/retry` | AI 재처리 |
| POST | `/companies/{cid}/documents/{id}/journal-entry` `{creditAccountId}` | 전표 생성·연결 |
| POST | `/internal/v1/documents/{id}/ai-result` | AI worker 콜백 (내부 토큰) |

FastAPI: `GET /health`, `POST /v1/extract`(파일 → 추출 결과, 디버그·데모용, 저장 안 함), Valkey worker.
