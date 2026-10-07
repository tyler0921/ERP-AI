# LedgerGuard AI — 개발 지침

전체 요구사항은 `docs/PROJECT_SPEC.md`가 기준이다. 진행 상황과 주요 설계 결정은 `README.md`에 있다.

## 핵심 원칙

- "알고리즘과 규칙이 판단하고, AI가 설명하며, 사람이 최종 승인한다."
- LLM은 금액·세금 계산, 정합성 검증, 최종 승인, 이상거래 최종 판정, DB 직접 수정을 하지 않는다. 설명·질의 이해·요약·보조 추천만 한다.
- 무료·오픈소스만 사용: Ollama, PaddleOCR, PostgreSQL + pgvector, Valkey, SeaweedFS. 유료 API Key 의존 금지.
- 서비스 책임: Next.js(frontend) / Spring Boot(비즈니스 로직, 인증, 대사, 승인, Audit) / FastAPI(OCR, ML, Embedding, RAG, LLM).
- 전체 환경은 `docker compose` 하나로 실행 가능해야 한다.
- 가상 데이터만 사용한다.

## 작업 방식

- 한 번에 전체를 만들지 않고 Phase 단위로 완성한다 (Phase 순서는 spec 참고, MVP = Phase 1~2 + 기본 Dashboard).
- 각 Phase: 목표 → Entity → DB Schema → API → Backend → 테스트 → Frontend → (AI) → 테스트/리팩토링.
- 설계 변경 시 기존 아키텍처와의 충돌을 먼저 검토한다.
- 구현 시 고려: 책임 분리, Validation, 트랜잭션, 보안(회사 격리), Audit Log, AI 실패 처리, 데이터 정합성, 테스트 가능성.
- AI 결과에는 confidence/근거를 저장하고, 사용자가 수정 가능해야 하며, 수정은 Audit Log에 남긴다.
- Phase가 끝나면 README의 진행 상황 표를 갱신한다.

## Backend 컨벤션 (기존 코드 기준)

- 패키지는 도메인 단위(`com.ledgerguard.<domain>`), DTO는 `<Domain>Dtos` record 모음.
- 회사 리소스는 `/api/v1/companies/{companyId}/**`, 접근 확인은 `CompanyAccessService`. 남의 회사 리소스는 404.
- 금액: `NUMERIC(19,2)` / `BigDecimal` / JSON 문자열. 비교는 `compareTo`.
- 물리 삭제 대신 `VOID` + 사유. 스키마는 Flyway만 관리 (`ddl-auto: none`).
- 쓰기 API는 같은 트랜잭션에서 `AuditService`로 before/after를 기록한다.
- 오류는 `ApiException(ErrorCode)` → `{ code, message, details[], timestamp }`.
- 판단 로직(검증, Match Score 등)은 Spring/DB 의존성 없는 순수 클래스로 분리해 단위 테스트한다 (`JournalEntryValidator`, `BankCsvParser`, `MatchScorer`).
- 백엔드 테스트는 경로의 한글·공백 때문에 ASCII 드라이브로 매핑해 실행한다: `subst L: "D:\PC_1M\project\회계 AI"` 후 `L:\backend`에서 `gradlew.bat test` (JDK 21: `C:\Program Files\Eclipse Adoptium\jdk-21*`).

## Frontend 컨벤션 (기존 코드 기준)

- Next.js 16 (App Router, Cache Components 켜짐). API가 학습 데이터와 다를 수 있으니 `frontend/node_modules/next/dist/docs/`를 먼저 확인한다. `middleware`는 `proxy.ts`로 바뀌었다.
- 브라우저는 `/api/backend/**`(BFF)만 호출한다. 백엔드 URL·JWT를 클라이언트 코드에 두지 않는다. 호출은 `lib/api.ts`의 `api()` / `useApi()`.
- `useParams`/`useSearchParams`를 쓰는 클라이언트 컴포넌트는 `<Suspense>` 안에 둔다 (없으면 빌드 실패).
- 회사 화면은 `useCompany()`로 `base`(`/companies/{id}`)와 권한(`canWrite`, `isAdmin`)을 얻는다.
- 금액은 문자열로 받고, 계산은 `lib/money.ts`(BigInt cents), 표시는 `<Money>`.
- 디자인 토큰은 `globals.css`(`paper`, `ink`, `accent`, `danger` …). 공통 UI는 `components/ui.tsx`.

## AI 서비스 컨벤션 (ai-service)

- 추출·분류는 규칙 기반 순수 함수로 두고 `OcrEngine` 인터페이스 뒤의 OCR과 분리한다. 단위 테스트는 Paddle 없이 `tests/helpers.lines_of`로 OCR 줄을 만들어 쓴다.
- 추출기는 값을 계산으로 만들어내지 않는다. 못 찾으면 `MISSING`(confidence 0). 금액은 문자열("1250000.00")로 다룬다.
- PaddleOCR이 토큰 사이 공백을 자주 빼먹는다(`합계1,253,000원`). 정규식은 공백에 의존하지 않게 쓴다.
- 의존성은 `requirements.txt`에 정확한 버전으로 고정한다. 실제 OCR 테스트는 컨테이너 안에서 실행한다.
