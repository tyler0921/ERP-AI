# LedgerGuard AI

영수증·세금계산서·은행 거래내역·전표를 통합해 증빙 인식, 자동대사, 이상거래 탐지, 월마감 분석을 돕는 AI 기반 회계 업무 보조 플랫폼.
"알고리즘과 규칙이 판단하고, AI가 설명하며, 사람이 최종 승인한다."

전체 설계는 [`docs/PROJECT_SPEC.md`](docs/PROJECT_SPEC.md)를 따른다.

## 진행 상황

| Phase | 내용 | 상태 |
|---|---|---|
| **1** | **Core Accounting** — 인증, 회사/Role, 계정과목, 거래, 전표, Audit Log | **Backend 구현 완료** (Frontend 예정) |
| 2 | Bank Reconciliation | 예정 |
| 3~8 | Document AI, 이상거래 탐지, Local LLM, 월마감 Copilot, Q&A, RAG | 예정 |

## 구조

```
회계 AI/
├─ docker-compose.yml        # PostgreSQL(pgvector) + backend
├─ .env.example
├─ docs/PROJECT_SPEC.md      # 전체 설계 문서
└─ backend/                  # Spring Boot 3.3 / Java 21 / Gradle
   └─ src/main/java/com/ledgerguard/
      ├─ common/             # 오류 코드·응답 형식, 전역 예외 처리, 금액 직렬화
      ├─ security/           # JWT, SecurityConfig
      ├─ audit/              # Audit Log (기록 서비스 + 조회 API)
      ├─ user/               # 회원가입·로그인·토큰 재발급·내 정보
      ├─ company/            # 회사, 멤버/Role, 회사 단위 접근 제어
      ├─ account/            # 계정과목
      ├─ transaction/        # 거래
      └─ journal/            # 전표 + 차·대변 검증
```

## 실행

필요한 것: Docker (컴파일·테스트를 로컬에서 하려면 JDK 21).

```bash
cp .env.example .env
docker compose up --build
```

- API: http://localhost:8080/api/v1
- Swagger UI: http://localhost:8080/swagger-ui.html
- Health: http://localhost:8080/actuator/health

DB만 띄우고 백엔드를 IDE/Gradle로 실행할 수도 있다.

```bash
docker compose up -d postgres
cd backend
./gradlew bootRun
```

## 테스트

```bash
cd backend
./gradlew test
```

- `JournalEntryValidatorTest` — 전표 차·대변 검증 단위 테스트 (Docker 불필요)
- `AccountingApiIntegrationTest` — 실제 PostgreSQL 16(Testcontainers)과 Flyway로 인증, 회사 격리, 권한, 전표 검증, VOID, Audit Log, DB 안전망까지 검증 (**Docker 필요**)

## 주요 설계 결정

- **회사 격리:** 모든 `/companies/{companyId}/**` 요청은 `CompanyAccessService`에서 (사용자, 회사) 멤버십과 Role을 먼저 확인한다. 멤버가 아닌 회사와 다른 회사의 리소스는 존재 여부를 숨기기 위해 403이 아닌 **404**로 응답한다. 모든 조회 쿼리에는 `company_id` 조건이 들어간다.
- **금액:** DB `NUMERIC(19,2)`, 자바 `BigDecimal`, JSON은 **문자열**(`"1250000.00"`). 비교는 `compareTo`.
- **전표 균형 이중 방어:** 서비스 레이어 검증(`JournalEntryValidator`) + DB의 `DEFERRABLE INITIALLY DEFERRED` 제약 트리거(커밋 시점에 차변 합계 = 대변 합계 보장).
- **삭제 대신 취소(VOID):** 거래·전표는 물리 삭제하지 않고 `VOID` 상태와 사유로 남긴다. 전표가 연결된 거래는 수정·취소할 수 없고, 전표를 먼저 취소해야 한다.
- **Audit Log:** 모든 쓰기 API가 변경과 **같은 트랜잭션**에서 before/after(JSONB)를 남긴다. DB 트리거가 UPDATE/DELETE를 막아 추가만 가능하다. 로그인 성공/실패도 기록한다. (실패 이력이 롤백되지 않도록 로그인은 트랜잭션 밖에서 기록)
- **마지막 ADMIN 보호:** 회사 행을 `PESSIMISTIC_WRITE`로 잠그고 ADMIN 수를 확인해 동시 요청에서도 마지막 ADMIN이 사라지지 않는다.
- **스키마 관리:** Flyway가 단독으로 관리한다 (`spring.jpa.hibernate.ddl-auto=none`).
- **JWT:** Access 30분 / Refresh 14일. Refresh 토큰은 상태가 없는(stateless) 방식이라 서버에서 즉시 폐기할 수 없다. 폐기가 필요하면 이후 Valkey 기반 블랙리스트로 확장한다.

## API 요약 (`/api/v1`)

| 영역 | 엔드포인트 |
|---|---|
| Auth | `POST /auth/signup`, `/auth/login`, `/auth/refresh`, `GET /me` |
| 회사 | `POST·GET /companies`, `GET·PATCH /companies/{cid}` |
| 멤버 | `GET·POST /companies/{cid}/members`, `PATCH·DELETE …/members/{userId}` |
| 계정과목 | `GET·POST /companies/{cid}/accounts`, `PATCH …/accounts/{id}` |
| 거래 | `POST·GET /companies/{cid}/transactions`, `GET·PATCH …/{id}`, `POST …/{id}/void` |
| 전표 | `POST·GET /companies/{cid}/journal-entries`, `GET·PUT …/{id}`, `POST …/{id}/void` |
| Audit Log | `GET /companies/{cid}/audit-logs` |

권한: 조회는 ADMIN·ACCOUNTANT·VIEWER, 거래·전표·계정과목 변경은 ADMIN·ACCOUNTANT, 회사 정보와 멤버 관리는 ADMIN, Audit Log 조회는 ADMIN·ACCOUNTANT.

오류 응답은 항상 `{ code, message, details[], timestamp }` 형식이다. (예: `JOURNAL_NOT_BALANCED`)

## 개발 원칙

실서비스 금융 데이터가 아닌 가상 데이터만 사용한다. 유료 API Key에 의존하지 않는다. Phase 단위로 완성하고 각 Phase마다 테스트를 작성한다.
