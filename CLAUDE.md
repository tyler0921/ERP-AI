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
