# LedgerGuard AI

영수증·세금계산서·은행 거래내역·전표를 통합해 증빙 인식, 자동대사, 이상거래 탐지, 월마감 분석을 돕는 AI 기반 회계 업무 보조 플랫폼.
"알고리즘과 규칙이 판단하고, AI가 설명하며, 사람이 최종 승인한다."

전체 설계는 [`docs/PROJECT_SPEC.md`](docs/PROJECT_SPEC.md)를 따른다.

## 진행 상황

| Phase | 내용 | 상태 |
|---|---|---|
| **1** | **Core Accounting** — 인증, 회사/Role, 계정과목, 거래, 전표, Audit Log | **완료** (Backend + Frontend) |
| **2** | **Bank Reconciliation** — 은행 CSV 업로드, Match Score, 자동 제안, 승인/거절/수동 매칭/취소 ([설계](docs/phase2-bank-reconciliation.md)) | **완료** (1:1. 1:N·N:1은 데이터 모델만 준비) |
| **3** | **Document AI** — 영수증·세금계산서 업로드(SeaweedFS), Valkey 작업 큐, PaddleOCR, 규칙 기반 필드 추출·검증, 계정과목 추천, 승인, 전표 생성 ([설계](docs/phase3-document-ai.md)) | **완료** |
| 4~8 | 이상거래 탐지, Local LLM, 월마감 Copilot, Q&A, RAG | 예정 |

## 구조

```
회계 AI/
├─ docker-compose.yml        # PostgreSQL(pgvector), Valkey, SeaweedFS, backend, ai-service, frontend
├─ .env.example
├─ docs/PROJECT_SPEC.md      # 전체 설계 문서
├─ docs/phase2-bank-reconciliation.md
├─ docs/phase3-document-ai.md
├─ ai-service/               # FastAPI / Python 3.12 / PaddleOCR 3.7 (CPU)
│  ├─ app/ocr/               # OCR 엔진 인터페이스, PaddleOCR 구현, 이미지·PDF 로딩, 줄 묶기
│  ├─ app/extraction/        # 영수증·세금계산서 규칙 기반 필드 추출 (금액·날짜·사업자번호 패턴)
│  ├─ app/classification/    # 키워드 규칙 기반 계정과목 후보
│  ├─ app/worker.py          # Valkey 큐 소비 → 처리 → backend 내부 API 콜백
│  └─ scripts/make_samples.py # 가상 영수증·세금계산서 샘플 생성
├─ frontend/                 # Next.js 16 (App Router) / TypeScript / Tailwind 4
│  └─ src/
│     ├─ proxy.ts            # 페이지 접근 가드 (세션 쿠키 없으면 /login)
│     ├─ app/api/            # BFF: auth/login·logout, backend/[...path] 프록시
│     ├─ app/(auth)/         # 로그인, 회원가입
│     ├─ app/companies/      # 회사 선택·등록
│     ├─ app/c/[companyId]/  # Dashboard, 거래, 전표, 계정과목, 은행 대사, Audit Log, 설정
│     ├─ components/         # UI 프리미티브, 회사 셸·컨텍스트
│     └─ lib/                # API 클라이언트, 금액(BigInt) 계산, 전표 검증, 날짜(KST)
└─ backend/                  # Spring Boot 3.3 / Java 21 / Gradle
   └─ src/main/java/com/ledgerguard/
      ├─ common/             # 오류 코드·응답 형식, 전역 예외 처리, 금액 직렬화
      ├─ security/           # JWT, SecurityConfig
      ├─ audit/              # Audit Log (기록 서비스 + 조회 API)
      ├─ user/               # 회원가입·로그인·토큰 재발급·내 정보
      ├─ company/            # 회사, 멤버/Role, 회사 단위 접근 제어
      ├─ account/            # 계정과목
      ├─ transaction/        # 거래
      ├─ journal/            # 전표 + 차·대변 검증
      ├─ bank/               # 은행 계좌, CSV 파서(UTF-8/CP949), 업로드·중복 방지
      ├─ reconciliation/     # Match Score 엔진, 자동 대사, 승인/거절/수동 매칭/취소
      └─ document/           # 증빙 업로드·검증(DocumentValidator)·추천·승인·전표 생성, SeaweedFS·Valkey 어댑터
```

## 실행

필요한 것: Docker (컴파일·테스트를 로컬에서 하려면 JDK 21, Node 24).

```bash
docker compose up --build
```

모든 환경 변수는 `docker-compose.yml`에 개발용 기본값이 있다. 바꾸려면 루트에 `.env`를 만들어 덮어쓴다 (예: `JWT_SECRET`, `INTERNAL_API_TOKEN`, `POSTGRES_PASSWORD`, 포트). 개발 외 환경에서는 `JWT_SECRET`과 `INTERNAL_API_TOKEN`을 반드시 교체한다.

> 첫 실행 때 ai-service가 OCR 모델(약 수십 MB)을 내려받아 `ledgerguard-ocr-models` 볼륨에 캐시한다. ai-service 이미지는 PaddlePaddle 때문에 2GB 이상이다.

- 웹: http://localhost:3000 (회원가입 → 회사 등록 → 기본 계정과목 자동 생성)
- API: http://localhost:8080/api/v1
- Swagger UI: http://localhost:8080/swagger-ui.html
- Health: http://localhost:8080/actuator/health
- AI 서비스: http://localhost:8000/health, http://localhost:8000/docs (`POST /v1/extract`로 파일 하나를 바로 추출해 볼 수 있다. 저장하지 않음)
- 샘플 파일(가상 데이터): 화면의 "샘플" 링크, 또는 `frontend/public/samples/`

DB와 백엔드만 컨테이너로 띄우고 프론트엔드를 개발 모드로 실행할 수도 있다.

```bash
docker compose up -d postgres backend
cd frontend
npm install
npm run dev          # BACKEND_URL 기본값 http://localhost:8080
```

## 테스트

```bash
cd backend
./gradlew test
```

> **Windows 주의:** 프로젝트 경로에 한글·공백(`회계 AI`)이 있으면 Gradle 테스트 워커가 테스트 클래스를 찾지 못한다(`ClassNotFoundException`). ASCII 드라이브로 매핑해서 실행한다.
>
> ```powershell
> subst L: "D:\PC_1M\project\회계 AI"; cd L:\backend; .\gradlew.bat test; cd D:\; subst L: /D
> ```

- `JournalEntryValidatorTest` — 전표 차·대변 검증 단위 테스트 (Docker 불필요)
- `AccountingApiIntegrationTest` — 실제 PostgreSQL 16(Testcontainers)과 Flyway로 인증, 회사 격리, 권한, 전표 검증, VOID, Audit Log, DB 안전망까지 검증 (**Docker 필요**)
- `BankCsvParserTest` — 컬럼 별칭, CP949, 헤더 앞 제목행, 금액+구분/부호 금액, 행 단위 오류(전부 거부)
- `MatchScorerTest` — 항목별 배점, 금액 불일치 시 70점 미만 보장, 날짜 감점, 과거 패턴, 등급 경계, 거래처 정규화
- `DocumentValidatorTest` — 합계 불일치, 부가세율, 사업자번호 체크섬, 공급받는자 확인, 필수값, 미래 날짜, 중복 의심, 낮은 신뢰도
- `DocumentApiIntegrationTest` — 업로드(매직 바이트·중복·경로 제거) → AI 콜백(토큰·시도 번호·멱등) → 검증·추천 → 수정·승인·전표 생성, 과거 이력 추천, 실패·재시도·큐 장애·시간 초과, 회사 격리·VIEWER (**Docker 필요**, 저장소·큐는 메모리 구현)
- `ReconciliationApiIntegrationTest` — 업로드·재업로드 중복 방지 → 자동 대사 → 승인/거절/재실행/수동 매칭/취소 → 현황 집계 → Audit Log → DB 안전망(이중 확정, 금액 균형), 회사 격리·VIEWER 권한 (**Docker 필요**)

```bash
cd frontend
npm test             # vitest
npm run lint && npm run build
```

- `money.test.ts` — 금액 입력 정규화, BigInt 합산(부동소수점 오차·Number 정밀도 초과 없음), 표시 형식
- `journal.test.ts` — 전표 폼 검증(1:N 균형, 차액, 차·대변 동시 입력, 행 번호 오류 메시지)
- `dates.test.ts` — KST 기준 날짜, 월 범위

```bash
# ai-service (Paddle 없이 추출·분류 로직만)
cd ai-service && pip install -r requirements-dev.txt && pytest
# 실제 PaddleOCR까지 (컨테이너 안에서, 가상 샘플 이미지·PDF를 OCR)
docker compose run --rm -u root -v "$PWD/ai-service:/src" -w /src ai-service sh -c "pip install -q pytest && python -m pytest -q"
```

- `test_patterns.py` — 금액/날짜/사업자번호 패턴, 체크섬, OCR이 공백을 빼먹는 경우, 줄 묶기
- `test_extraction.py` — 영수증(라벨·추정), 세금계산서 표 양식(x 위치로 공급자/공급받는자 구분), 계정과목 규칙
- `test_ocr_samples.py` — 실제 PaddleOCR로 가상 영수증 PNG·세금계산서 PDF 전체 파이프라인 (Paddle 있는 환경에서만)

## 주요 설계 결정

- **회사 격리:** 모든 `/companies/{companyId}/**` 요청은 `CompanyAccessService`에서 (사용자, 회사) 멤버십과 Role을 먼저 확인한다. 멤버가 아닌 회사와 다른 회사의 리소스는 존재 여부를 숨기기 위해 403이 아닌 **404**로 응답한다. 모든 조회 쿼리에는 `company_id` 조건이 들어간다.
- **금액:** DB `NUMERIC(19,2)`, 자바 `BigDecimal`, JSON은 **문자열**(`"1250000.00"`). 비교는 `compareTo`.
- **전표 균형 이중 방어:** 서비스 레이어 검증(`JournalEntryValidator`) + DB의 `DEFERRABLE INITIALLY DEFERRED` 제약 트리거(커밋 시점에 차변 합계 = 대변 합계 보장).
- **삭제 대신 취소(VOID):** 거래·전표는 물리 삭제하지 않고 `VOID` 상태와 사유로 남긴다. 전표가 연결된 거래는 수정·취소할 수 없고, 전표를 먼저 취소해야 한다.
- **Audit Log:** 모든 쓰기 API가 변경과 **같은 트랜잭션**에서 before/after(JSONB)를 남긴다. DB 트리거가 UPDATE/DELETE를 막아 추가만 가능하다. 로그인 성공/실패도 기록한다. (실패 이력이 롤백되지 않도록 로그인은 트랜잭션 밖에서 기록)
- **마지막 ADMIN 보호:** 회사 행을 `PESSIMISTIC_WRITE`로 잠그고 ADMIN 수를 확인해 동시 요청에서도 마지막 ADMIN이 사라지지 않는다.
- **스키마 관리:** Flyway가 단독으로 관리한다 (`spring.jpa.hibernate.ddl-auto=none`).
- **JWT:** Access 30분 / Refresh 14일. Refresh 토큰은 상태가 없는(stateless) 방식이라 서버에서 즉시 폐기할 수 없다. 폐기가 필요하면 이후 Valkey 기반 블랙리스트로 확장한다.
- **Frontend BFF:** 브라우저는 같은 출처의 Next.js만 호출한다. JWT는 `httpOnly` 쿠키(`lg_at`/`lg_rt`)에만 저장해 JS에 노출하지 않고(XSS 토큰 탈취 방지), `/api/backend/[...path]`가 `Authorization` 헤더를 붙여 Spring으로 전달한다. 401이면 Refresh 토큰으로 한 번 재발급 후 재시도한다. 경로 세그먼트는 화이트리스트 정규식으로 검사해 경로 조작을 막는다. CORS 없이 동작한다.
- **프론트엔드 금액 계산:** 화면에서도 `Number`로 금액을 계산하지 않는다. 전표 입력의 합계·차액은 전(cents) 단위 `BigInt`로 계산하고, 최종 검증은 서버가 한다. 클라이언트 검증은 즉시 피드백용이다.
- **은행 대사 — 규칙이 판단, 사람이 확정:** `MatchScorer`(금액 40·날짜 20·거래처 20·메모 10·과거 패턴 10)가 점수와 근거(`scoreDetail` JSONB)를 만들고, 70점 이상만 제안한다. 금액이 다르면 최대 60점이라 자동 제안되지 않는다. 제안은 승인해야 확정되고, 거절한 쌍은 다시 제안하지 않는다. LLM은 사용하지 않는다.
- **전표의 은행 금액:** 전표 전체 금액이 아니라 은행 계좌에 연결된 계정과목(예: 1020 보통예금)의 움직임(출금=대변, 입금=차변)을 비교한다.
- **대사 데이터 정합성:** 계좌 행 잠금으로 같은 계좌의 대사 작업을 직렬화하고, DB 부분 유니크 인덱스(은행 거래·전표는 확정 매칭에 한 번만)와 지연 제약 트리거(확정 매칭은 은행 금액 = 전표 금액)가 이중으로 막는다. 확정 매칭된 전표는 수정·취소할 수 없다(`JOURNAL_RECONCILED`).
- **CSV 업로드:** 전부 성공 아니면 전부 실패(행 번호와 사유 반환). 같은 거래는 내용 해시(dedupeKey)로 건너뛰므로 기간이 겹치는 파일을 다시 올려도 안전하다. 계좌번호는 끝 4자리만 저장한다.
- **Document AI — 비동기 처리:** 업로드는 파일을 SeaweedFS에 저장하고 DB 커밋 **이후** Valkey 큐에 작업을 넣는다(커밋 전에 넣으면 worker가 없는 문서를 처리할 수 있음). AI worker가 PaddleOCR → 규칙 기반 추출 → 계정과목 후보를 만든 뒤 backend 내부 API(`X-Internal-Token`, 상수 시간 비교)로 결과를 보낸다. 결과는 현재 시도(`attempt`)의 `PENDING` 문서에만 반영되므로 중복·지연 콜백은 무시된다. 큐 장애·OCR 실패·15분 무응답은 `FAILED`가 되어 재시도하거나 사람이 직접 입력할 수 있다.
- **AI는 추출, 판단은 규칙과 사람:** 추출기는 찾지 못한 값을 계산으로 만들어내지 않는다(비워 두고 confidence 0). 합계·부가세율·사업자번호 체크섬·중복 등 검증은 Spring `DocumentValidator`가 하고, 오류가 있으면 승인할 수 없다. 필드별 confidence와 근거(OCR 원문 줄·규칙)를 저장해 화면에 보여주고, 사람의 수정은 before/after로 Audit Log에 남는다.
- **계정과목 추천:** 같은 거래처(사업자번호)의 과거 승인 이력(0.95)이 키워드 규칙(최대 0.85)보다 우선한다. 승인 기록에는 추천을 따랐는지(`followedRecommendation`)가 남는다.
- **파일 보안:** 형식은 확장자가 아니라 매직 바이트로 판별하고, 저장 경로는 서버가 UUID로 만든다(worker도 경로 형식을 다시 검사). SeaweedFS는 외부에 포트를 열지 않고, 파일은 권한 확인 후 Spring이 `nosniff`로 내려준다. 같은 파일(SHA-256)은 다시 올릴 수 없다.
- **PaddleOCR 설정:** `lang="korean"`만 주면 범용 인식 모델이 선택돼 한글을 못 읽으므로 `korean_PP-OCRv5_mobile_rec`를 명시한다. Paddle 3.3 CPU의 oneDNN 버그 때문에 `enable_mkldnn=False`.
- **권한별 UI:** 쓰기 버튼은 Role에 따라 숨기지만, 실제 권한 검사는 항상 백엔드가 한다. 아직 구현되지 않은 Phase 메뉴는 사이드바에 비활성으로 표시한다.

## API 요약 (`/api/v1`)

| 영역 | 엔드포인트 |
|---|---|
| Auth | `POST /auth/signup`, `/auth/login`, `/auth/refresh`, `GET /me` |
| 회사 | `POST·GET /companies`, `GET·PATCH /companies/{cid}` |
| 멤버 | `GET·POST /companies/{cid}/members`, `PATCH·DELETE …/members/{userId}` |
| 계정과목 | `GET·POST /companies/{cid}/accounts`, `PATCH …/accounts/{id}` |
| 거래 | `POST·GET /companies/{cid}/transactions`, `GET·PATCH …/{id}`, `POST …/{id}/void` |
| 전표 | `POST·GET /companies/{cid}/journal-entries`, `GET·PUT …/{id}`, `POST …/{id}/void` |
| 은행 계좌 | `GET·POST /companies/{cid}/bank-accounts`, `PATCH …/{id}`, `POST·GET …/{id}/imports` (CSV multipart) |
| 증빙 | `POST·GET /companies/{cid}/documents` (multipart `file`, `docType`), `GET …/summary`, `GET …/{id}`, `GET …/{id}/file`, `PUT …/{id}/fields`, `POST …/{id}/approve·reject·retry·journal-entry` |
| 내부(AI 서비스) | `POST /internal/v1/documents/{id}/ai-result` (`X-Internal-Token`) |
| 은행 대사 | `GET /companies/{cid}/bank-transactions`, `GET …/{id}/candidates`, `POST /reconciliation/run`, `GET /reconciliation/summary`, `POST /reconciliation/matches`, `GET …/matches/{id}`, `POST …/{id}/confirm·reject·cancel` |
| Audit Log | `GET /companies/{cid}/audit-logs` |

권한: 조회는 ADMIN·ACCOUNTANT·VIEWER, 거래·전표·계정과목·은행 대사·증빙 변경은 ADMIN·ACCOUNTANT, 회사 정보와 멤버 관리는 ADMIN, Audit Log 조회는 ADMIN·ACCOUNTANT.

오류 응답은 항상 `{ code, message, details[], timestamp }` 형식이다. (예: `JOURNAL_NOT_BALANCED`)

## 개발 원칙

실서비스 금융 데이터가 아닌 가상 데이터만 사용한다. 유료 API Key에 의존하지 않는다. Phase 단위로 완성하고 각 Phase마다 테스트를 작성한다.
