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
| **4** | **Anomaly Detection** — 규칙 엔진(10개 규칙), IsolationForest(FastAPI), Risk Score·Level, 이상거래 화면·검토(확정/정상/수준 조정/다시 열기), 탐지 기준 설정 ([설계](docs/phase4-anomaly-detection.md)) | **완료** |
| **5** | **Local LLM** — Ollama(qwen2.5 7B, GPU 선택), LLM Provider 인터페이스, 이상거래 설명·분석 요약(Valkey 큐 비동기), 출력 검증·템플릿 대체, 수정·다시 생성 ([설계](docs/phase5-local-llm.md)) | **완료** |
| **6** | **Closing Copilot** — 월별 손익 집계(계정과목·거래처), 전월·전년 동월 비교, 주요 변동 탐지, 마감 체크리스트·진행률, 마감 확정·취소(스냅샷·확정 이후 변경 표시), 월별 추이, 월마감 LLM 설명(증감 방향 검증) ([설계](docs/phase6-closing-copilot.md)) | **완료** |
| **7** | **AI Copilot (Q&A)** — 자연어 질문 → LLM Query Planner(Tool 5개 중 선택, SQL 없음) → Spring 계획 검증·읽기 전용 Query Tool 실행 → LLM 답변(검증·요약 대체), 규칙 Planner 대체, 해석·답변 수정(Audit) ([설계](docs/phase7-copilot.md)) | **완료** |
| 8 | 회계 규정 RAG | 예정 |

## 구조

```
회계 AI/
├─ docker-compose.yml        # PostgreSQL(pgvector), Valkey, SeaweedFS, Ollama, backend, ai-service, frontend
├─ docker-compose.gpu.yml    # (선택) Ollama를 NVIDIA GPU로 실행
├─ .env.example
├─ docs/PROJECT_SPEC.md      # 전체 설계 문서
├─ docs/phase2-bank-reconciliation.md
├─ docs/phase3-document-ai.md
├─ docs/phase4-anomaly-detection.md
├─ docs/phase5-local-llm.md
├─ docs/phase6-closing-copilot.md
├─ docs/phase7-copilot.md
├─ ai-service/               # FastAPI / Python 3.12 / PaddleOCR 3.7 (CPU)
│  ├─ app/ocr/               # OCR 엔진 인터페이스, PaddleOCR 구현, 이미지·PDF 로딩, 줄 묶기
│  ├─ app/extraction/        # 영수증·세금계산서 규칙 기반 필드 추출 (금액·날짜·사업자번호 패턴)
│  ├─ app/classification/    # 키워드 규칙 기반 계정과목 후보
│  ├─ app/llm/               # LLM Provider(Ollama), 설명 프롬프트·출력 검증·템플릿 대체, LLM 큐 worker
│  ├─ app/copilot/           # Copilot Query Planner: LLM 계획(구조화 출력)·형식 검증·보정, 키워드 규칙 Planner
│  ├─ app/anomaly/           # 이상탐지 ML: 특성 변환, IsolationForest(AnomalyModel 인터페이스), robust z 근거
│  ├─ app/worker.py          # Valkey 큐 소비 → 처리 → backend 내부 API 콜백
│  ├─ scripts/make_samples.py # 가상 영수증·세금계산서 샘플 생성
│  └─ scripts/make_anomaly_sample.py # 이상거래 데모용 6개월치 가상 은행 CSV 생성
├─ frontend/                 # Next.js 16 (App Router) / TypeScript / Tailwind 4
│  └─ src/
│     ├─ proxy.ts            # 페이지 접근 가드 (세션 쿠키 없으면 /login)
│     ├─ app/api/            # BFF: auth/login·logout, backend/[...path] 프록시
│     ├─ app/(auth)/         # 로그인, 회원가입
│     ├─ app/companies/      # 회사 선택·등록
│     ├─ app/c/[companyId]/  # Dashboard, 거래, 전표, 계정과목, 은행 대사, 증빙, 이상거래, 월마감, AI Copilot, Audit Log, 설정
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
      ├─ document/           # 증빙 업로드·검증(DocumentValidator)·추천·승인·전표 생성, SeaweedFS·Valkey 어댑터
      ├─ explanation/        # LLM 설명 저장·요청(커밋 후 큐)·콜백 반영·수정·시간 초과 (AiExplanation)
      ├─ anomaly/            # 이상거래: 특성(AnomalyFeatureExtractor)·규칙(AnomalyRuleEngine)·점수(RiskScorer), ML 클라이언트, 검토
      ├─ closing/            # 월마감: 집계 쿼리, 손익 줄(ClosingStatement)·비교(ClosingMath)·주요 변동(VarianceDetector), 체크리스트, 마감 확정
      └─ copilot/            # AI Copilot: 계획 검증(PlanValidator)·기간(PeriodResolver)·계정(AccountResolver)·Query Tool(CopilotTools), 계획 콜백
```

## 실행

필요한 것: Docker (컴파일·테스트를 로컬에서 하려면 JDK 21, Node 24).

```bash
docker compose up --build
# NVIDIA GPU가 있으면 (LLM 설명이 건당 수 초로 빨라진다)
docker compose -f docker-compose.yml -f docker-compose.gpu.yml up --build
```

모든 환경 변수는 `docker-compose.yml`에 개발용 기본값이 있다. 바꾸려면 루트에 `.env`를 만들어 덮어쓴다 (예: `JWT_SECRET`, `INTERNAL_API_TOKEN`, `POSTGRES_PASSWORD`, 포트). 개발 외 환경에서는 `JWT_SECRET`과 `INTERNAL_API_TOKEN`을 반드시 교체한다.

> 첫 실행 때 ai-service가 LLM 모델(`qwen2.5:7b-instruct`, 약 4.7GB)을 Ollama로 내려받아 `ledgerguard-ollama-models` 볼륨에 캐시한다. 내려받는 동안의 설명은 템플릿 요약으로 대체된다. 모델은 `.env`의 `LLM_MODEL`로 바꿀 수 있다.
>
> 첫 실행 때 ai-service가 OCR 모델(약 수십 MB)을 내려받아 `ledgerguard-ocr-models` 볼륨에 캐시한다. ai-service 이미지는 PaddlePaddle 때문에 2GB 이상이다.

- 웹: http://localhost:3000 (회원가입 → 회사 등록 → 기본 계정과목 자동 생성)
- API: http://localhost:8080/api/v1
- Swagger UI: http://localhost:8080/swagger-ui.html
- Health: http://localhost:8080/actuator/health
- AI 서비스: http://localhost:8000/health, http://localhost:8000/docs (`POST /v1/extract`로 파일 하나를 바로 추출해 볼 수 있다. 저장하지 않음)
- 샘플 파일(가상 데이터): 화면의 "샘플" 링크, 또는 `frontend/public/samples/`
- 이상거래 데모: 은행 대사 화면에서 계좌를 만들고 `bank-statement-anomaly-sample.csv`(2026-04~09, 134행)를 올린 뒤, 이상거래 화면에서 기본 기간(최근 3개월)으로 분석을 실행한다. 쪼개기 결제·승인 기준 직전 반복·중복 결제·평균 대비 고액·주말 심야 결제 7건이 탐지된다(CRITICAL 3, HIGH 2, MEDIUM 2). 소액 반복 결제(번개퀵)는 규칙 점수가 낮아 40점 미만이라 저장되지 않는다.

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
- `AnomalyRuleEngineTest` — 규칙 10개의 적중·경계(창 크기, 기준 이상/미만, 이력 부족), 배점 정렬, spec 예시 거래 CRITICAL, 평범한 거래 무적중
- `AnomalyFeatureExtractorTest` — 거래처 이력 특성(180일 평균·90일 빈도·간격), 같은 날 순서, ML 입력 형식, Risk Score 조합·Level 경계
- `AnomalyExplanationFactsTest` — LLM 근거 데이터: 화면과 같은 표시 형식(금액·시각·요일·배수), ML 미사용 시 사유만, 요약의 수준별·규칙별 건수와 상위 거래 순위
- `AnomalyApiIntegrationTest` — 은행 CSV → 분석(가짜 ML) → 목록·상세·요약 → 판정·수준 조정 → 재분석 시 판정 보존 → 다시 열기 → 새 근거 시 자동 재오픈 → ML 장애 시 규칙만으로 계산·해제 → ML 끔, 거래 원천의 증빙·영수증 중복(SQL)·취소 시 해제, 권한·회사 격리, DB 제약, **LLM 설명**(커밋 후 큐 등록·위험도 순, 내부 토큰·시도 번호 멱등, 근거가 같으면 재요청 안 함, 수정·Audit, 근거 변경 시 stale, 다시 생성, 시간 초과 후 늦은 결과, 큐 장애, 요약 재생성, 권한) (**Docker 필요**)
- `ClosingMathTest`·`VarianceDetectorTest`·`ClosingStatementTest` — 증감률(비교값 0·음수), 주요 변동 경계(1,000,000원·30%)·신규·소멸·정렬, 손익 합계·계정과목 정렬·거래처 표기 통합, LLM 근거 데이터
- `PlanValidatorTest`·`PeriodAndAccountTest`·`CopilotToolsTest` — 계획 허용 목록·범위·기본값·모르는 필드 제거, 기호 기간 → 월 범위(진행 중 기간 자르기, 미래 거부, 비교 기간), 계정과목 별칭·부분 일치·후보 안내, 손익·계정·거래처 순위·이상거래 결과와 요약 문장, 답변 근거 표시 형식
- `CopilotApiIntegrationTest` — 질문 → 계획 작업(커밋 후 큐, 질문·오늘·계정 이름만) → 계획 콜백(토큰·시도 번호) → Query Tool → 답변 작업·콜백, 답변 수정·다시 생성(Audit), 해석 수정(생성 중 답변 대체, Audit, 잘못된 해석 422), Tool별 결과(손익 전년 대비·분기, 계정 조건 거래처 순위, 이상거래 수준·상태, 월마감), 실패·재시도·실행 불가 계획·지원하지 않는 질문·동시 3개 제한·시간 초과, 본인 질문만·회사 격리 (**Docker 필요**)
- `ClosingApiIntegrationTest` — 가상 장부(2025-09·2026-08·2026-09)로 보고서 집계(취소 전표 제외)·비교·주요 변동·체크리스트·추이, 잘못된 월, LLM 설명 요청·재생성·수정·stale, 마감 확정(남은 항목 기록)·확정 이후 변경 표시·취소·재확정, 권한·회사 격리 (**Docker 필요**)

```bash
cd frontend
npm test             # vitest
npm run lint && npm run build
```

- `money.test.ts` — 금액 입력 정규화, BigInt 합산(부동소수점 오차·Number 정밀도 초과 없음), 표시 형식
- `journal.test.ts` — 전표 폼 검증(1:N 균형, 차액, 차·대변 동시 입력, 행 번호 오류 메시지)
- `dates.test.ts` — KST 기준 날짜, 월 범위
- `anomaly.test.ts` — Risk Level 구간(서버와 동일), 기본 분석 기간, 기간 검증(366일), 특성 값 표시
- `copilot.test.ts` — 해석 편집 폼 정리(도구·기간 유형에 맞지 않는 값 제거), 폼 검증, 해석 표시
- `closing.test.ts` — 월 이동(연도 경계), KST 기준 이번 달·기본 마감 월, 선택 가능한 월, 축 눈금, 만원 표기

```bash
# ai-service (Paddle 없이 추출·분류 로직만)
cd ai-service && pip install -r requirements-dev.txt && pytest
# 실제 PaddleOCR까지 (컨테이너 안에서, 가상 샘플 이미지·PDF를 OCR)
docker compose run --rm -u root -v "$PWD/ai-service:/src" -w /src ai-service sh -c "pip install -q pytest && python -m pytest -q"
```

- `test_patterns.py` — 금액/날짜/사업자번호 패턴, 체크섬, OCR이 공백을 빼먹는 경우, 줄 묶기
- `test_extraction.py` — 영수증(라벨·추정), 세금계산서 표 양식(x 위치로 공급자/공급받는자 구분), 계정과목 규칙
- `test_ocr_samples.py` — 실제 PaddleOCR로 가상 영수증 PNG·세금계산서 PDF 전체 파이프라인 (Paddle 있는 환경에서만)
- `test_anomaly.py` — 이상치가 가장 높은 점수, robust z 근거, 같은 입력 같은 점수, 표본 부족, 결측값 대체, 계정과목 빈도 인코딩, API
- `test_llm.py` — 근거 그대로인 설명 통과, 만원·반올림 표기 허용, 근거에 없는 숫자·단정 표현·외국 문자·뒤집힌 "기준 초과"·길이·JSON 오류 시 템플릿 대체(거부된 출력 보존), LLM 장애, 템플릿은 항상 검증 통과, worker 메시지, Ollama 요청 형식(MockTransport)
- `test_copilot.py` — 규칙 Planner(spec 예시 질문 포함), 기간 표현·비교 표현, 답할 수 없는 질문, LLM 계획 형식 오류 시 대체, LLM 계획 보정(기간·계정과목, 실제 오류 사례), Copilot 답변 검증(항목 증감 방향, 근거 없는 순위 표현, 금액 없는 답변), 요약 템플릿, worker의 계획 콜백 경로
- `test_closing_llm.py` — 월마감 설명: 근거 그대로인 설명 통과, 매출·비용·영업이익 증감 방향을 뒤집으면 대체(합계 이름 단위로 판단, 애매하면 통과), 근거 밖 숫자, 템플릿(변동·남은 항목 유무), worker 처리

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
- **이상거래 — 규칙이 판단, ML은 보조, 사람이 판정:** Spring의 순수 클래스 `AnomalyFeatureExtractor`(거래처 빈도·평균·간격 등) → `AnomalyRuleEngine`(중복 결제·쪼개기·승인 기준 직전·평균 대비 고액·신규 거래처·영수증 중복·증빙 없음·반복·주말·심야·계정 급증) → FastAPI IsolationForest → `RiskScorer`. Risk Score = 규칙 점수 + 40 × ML 점수(최대 100)라 ML만으로는 MEDIUM까지만 오른다. 40점 이상만 저장하고, 규칙별 사유·판단 값과 ML 근거(모집단 중앙값 대비 robust z가 큰 특성)를 함께 남긴다. 점수 계산에는 LLM을 쓰지 않는다(설명만 Phase 5).
- **분석 대상과 모집단:** 은행 거래·거래의 출금만 분석한다. 같은 결제가 두 원천에 모두 있을 수 있어 원천별 모집단 안에서만 비교한다. 계정과목·증빙은 연결 전표(은행 거래는 확정 대사)를 따라 찾는다. 대상 기간 앞 180일을 기준선으로, 뒤 7일을 중복 결제 창으로 함께 읽는다.
- **IsolationForest:** 저장된 모델 없이 요청마다 회사·원천 모집단으로 학습하고 `random_state`를 고정해 재현 가능하다. 표본이 30건 미만이면 건너뛰고, AI 서비스 장애·시간 초과(20초) 때는 규칙 점수만으로 계산해 `mlStatus=UNAVAILABLE`을 남긴다. Spring ↔ FastAPI는 동기 HTTP이며 모델은 `AnomalyModel` 인터페이스 뒤에 있다.
- **재분석과 사람의 판정:** 재분석은 이상 확정·정상 처리된 결과를 덮어쓰지 않는다. 단, 판정 이후 새 규칙이 적중하면(새 근거) 다시 검토 대기로 열고 이전 판정을 `context.previousReview`에 남긴다. 기준 미만이 되거나 원천 거래가 취소되면 해제(CLEARED)된다. 분석·판정은 회사 행 잠금으로 직렬화해 동시에 실행돼도 판정이 사라지지 않는다. 판정에는 의견이 필수이고(DB CHECK), 수준 조정은 `adjusted_level`로 따로 저장해 산정값과 구분한다(`effective_level`은 DB 생성 컬럼).
- **탐지 기준:** 승인 기준 금액·직전 비율·증빙 기준·신규 거래처 기준·평균 대비 배수·심야 시간·ML 사용은 회사별 설정(ADMIN)이며, 분석마다 그때의 설정 스냅샷을 저장한다.
- **LLM은 설명만:** 이상 여부·점수·금액은 Phase 4의 규칙과 알고리즘이 정하고, LLM(Ollama 로컬 모델)은 Spring이 미리 계산해 표시 형식으로 만든 근거 데이터(facts)를 문장으로 옮긴다. 원본 데이터는 보내지 않는다. 입력 facts·모델·프롬프트 버전·소요 시간을 함께 저장해 무엇을 보고 설명했는지 확인할 수 있다.
- **LLM 출력 검증:** 설명의 숫자는 근거 데이터의 숫자(만원·억 단위, 반올림 포함)여야 하고, 사기·횡령·위반 같은 단정 표현, 한자·영어 섞임, 근거에 없는 "기준 초과" 주장(7B 모델이 "기준 미만"을 뒤집는 오류가 실제로 반복됨)은 걸러낸다. 걸리면 규칙 근거로 만든 템플릿 요약(FALLBACK)을 쓰고 거부된 출력은 남긴다. Ollama가 없거나 실패해도 같은 방식으로 대체되어 기능은 멈추지 않는다. 다만 검증은 숫자·표현 수준이라 문장 해석의 미묘한 오류까지 막지는 못하므로, 화면에서 규칙 근거를 항상 함께 보여주고 사람이 수정할 수 있게 했다.
- **LLM 비동기 처리:** 분석 커밋 후 OPEN 결과(위험도 높은 순)와 분석 요약을 별도 Valkey 큐에 넣고, ai-service의 LLM worker(OCR worker와 다른 스레드)가 처리해 내부 API로 콜백한다. 근거가 이전과 같으면 다시 만들지 않고(factsHash), 현재 시도의 결과만 반영하며, 30분 무응답은 FAILED가 되어 다시 생성할 수 있다. 사람이 수정한 설명은 재분석이 덮어쓰지 않고 근거가 바뀌었다는 표시(stale)만 남긴다. RTX 5050(8GB) 기준 설명 한 건 약 2~5초, 첫 호출은 모델 로딩으로 약 1분.
- **월마감 — 계산은 DB와 규칙, 설명은 LLM, 확정은 사람:** 월별 손익은 취소되지 않은 전표를 DB에서 집계하고(수익 = 대변−차변, 비용 = 차변−대변, 영업이익 = 매출−비용의 간이 손익), 전월·전년 동월 비교와 주요 변동(증감 1,000,000원 이상 그리고 30% 이상, 또는 신규·소멸)은 순수 클래스가 계산한다. 거래처별 비용은 연결 거래 → 승인 증빙 → 확정 대사된 은행 거래 순으로 거래처를 찾고, 표기가 다른 같은 거래처는 정규화해 합친다. 집계는 매번 다시 계산하고 저장하지 않는다.
- **마감 확정:** ADMIN이 의견과 함께 확정하면 그 시점의 합계·체크리스트·주요 변동을 스냅샷으로 남긴다. 체크리스트가 남아 있어도 확정할 수 있지만 남은 항목이 스냅샷과 Audit Log에 기록된다. 이번 Phase는 마감된 월의 전표 수정을 막지 않고(기간 잠금은 범위 밖), 확정 이후 합계가 바뀌면 "확정 이후 변경됨"을 보여준다. 이번 달은 마감할 수 없다.
- **월마감 설명:** 집계가 수시로 바뀌므로 자동 생성하지 않고 사람이 요청할 때만 만든다. LLM에는 계산된 문장(예: "8,000,000원 증가 (+66.7%)")만 보내고, 출력 검증에 매출·비용·영업이익의 증감 방향이 근거와 반대인지 확인하는 규칙을 더했다.
- **AI Copilot — LLM은 계획만 고르고 조회·계산은 Spring:** 질문은 LLM(구조화 출력)이 미리 정의된 Query Tool 5개(손익·계정·거래처 순위·이상거래·월마감) 중 하나와 파라미터(기호 기간·계정 이름·정렬·수준·건수)로 바꾼다. SQL은 만들지 않고, 회사는 URL 경로에서만 정해진다. Spring이 계획을 허용 목록으로 다시 검증하고 기간을 실제 월 범위로 계산한 뒤 읽기 전용 쿼리를 실행한다. LLM이 실패하거나 형식이 틀리면 키워드 규칙 Planner로 대체하고, 실행할 수 없는 계획(없는 계정·한 달이 아닌 마감 질문)은 계획을 남긴 채 실패로 두어 사람이 해석을 고쳐 다시 조회한다.
- **Copilot 답변:** Spring이 결과로 요약 문장을 만들어 LLM 답변의 근거이자 대체 답변으로 쓴다. 실제 7B 출력에서 본 오류(기간 바꿔치기, 계정 무시, 근거 없는 "가장 큰", 숫자 없는 추론)는 ai-service의 보정·검증 규칙으로 막았다. 결과 표와 계산 요약을 답변 옆에 항상 보여준다. 질문·답변은 질문한 사람만 보고, 동시에 해석 중인 질문은 3개까지다.
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
| 이상거래 | `GET·PUT /companies/{cid}/anomaly-settings`, `POST /anomalies/scan`, `GET /anomalies/scans`, `GET /anomalies`, `GET …/summary`, `GET …/{id}`, `POST …/{id}/review·reopen`, `PUT …/{id}/explanation`, `POST …/{id}/explanation/regenerate`, `POST …/scans/{id}/summary/regenerate` |
| 월마감 | `GET /companies/{cid}/closing/{yyyy-MM}`, `GET …/closing/trend?to=&months=`, `POST·PUT …/closing/{yyyy-MM}/explanation` (`?force=true` 다시 생성), `POST …/closing/{yyyy-MM}/close·reopen` |
| AI Copilot | `POST·GET /companies/{cid}/copilot/questions`, `GET …/{id}`, `PUT …/{id}/plan`, `POST …/{id}/retry`, `PUT …/{id}/answer`, `POST …/{id}/answer/regenerate` |
| 내부(Copilot 계획) | `POST /internal/v1/copilot/questions/{id}/plan` (`X-Internal-Token`) |
| AI 서비스(FastAPI) | `POST /v1/anomaly/score` (backend가 호출, 저장 안 함), `POST /v1/explain`, `POST /v1/copilot/plan` (디버그·데모용) |
| 내부(LLM worker) | `POST /internal/v1/explanations/{id}/result` (`X-Internal-Token`) |
| Audit Log | `GET /companies/{cid}/audit-logs` |

권한: 조회는 ADMIN·ACCOUNTANT·VIEWER, 거래·전표·계정과목·은행 대사·증빙 변경과 이상거래 분석·판정·AI 설명(이상거래·월마감) 수정과 다시 생성은 ADMIN·ACCOUNTANT, 회사 정보·멤버 관리·탐지 기준 변경·월마감 확정과 취소는 ADMIN, Audit Log 조회는 ADMIN·ACCOUNTANT. AI Copilot 질문은 모든 멤버가 할 수 있고(조회만 함) 본인 질문만 보고 고칠 수 있다.

오류 응답은 항상 `{ code, message, details[], timestamp }` 형식이다. (예: `JOURNAL_NOT_BALANCED`)

## 개발 원칙

실서비스 금융 데이터가 아닌 가상 데이터만 사용한다. 유료 API Key에 의존하지 않는다. Phase 단위로 완성하고 각 Phase마다 테스트를 작성한다.
