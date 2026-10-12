# Phase 8 — Accounting RAG (회계 규정) 설계

## 1. 목표

회사가 내부 회계 규정(경비 처리 규정, 법인카드 사용 규정, 계정과목 가이드, 처리 사례, 승인 규정)을 등록하고,
담당자가 자연어로 물으면 **등록된 규정 발췌를 근거로, 출처를 표시해** 답한다.

```
등록: 업로드(Spring, SeaweedFS) → 색인 작업(Valkey) → [ai-service] 텍스트 추출 → Chunking → 임베딩(sentence-transformers)
      → 콜백 → [Spring] pgvector 저장
질문: [Spring] → [ai-service] 질문 임베딩(동기) → [Spring] pgvector 검색 Top-K(회사 조건) → 답변 작업(LLM 큐)
      → [ai-service] Ollama 답변(인용 번호) → 출력 검증 → 콜백
```

- 근거는 검색된 규정 발췌뿐이다. LLM은 발췌에 없는 내용을 만들지 않고, 각 문장에 출처 번호 `[1]`을 단다.
- 모든 검색은 회사 조건을 SQL에 넣는다. 다른 회사 규정은 검색되지 않는다.
- 임베딩 서비스가 실패하면 키워드 검색으로 대체하고(`retrievalMode = KEYWORD`), LLM이 실패하면 발췌 목록을 그대로 보여준다(FALLBACK).
- 서비스 책임: 파일 저장·메타데이터·청크 저장·검색·권한·Audit은 Spring, 텍스트 추출·청킹·임베딩·LLM은 ai-service. ai-service는 DB에 접근하지 않는다.

## 2. 문서와 색인

| 항목 | 내용 |
|---|---|
| 형식 | PDF(텍스트 레이어), DOCX, TXT·MD(UTF-8 또는 CP949). 최대 10MB. 형식은 바이트로 판별 |
| 분류 | 경비 처리 규정, 법인카드 사용 규정, 계정과목 가이드, 회계 처리 사례, 내부 승인 규정, 기타 |
| 상태 | `PENDING`(색인 중) → `INDEXED` / `FAILED`(다시 색인 가능), `ARCHIVED`(보관, 검색 제외, 사유 필수) |
| 중복 | 같은 회사에 같은 파일(SHA-256)은 다시 올릴 수 없다 |

- 텍스트 추출: PDF는 pypdfium2 텍스트 레이어(스캔 PDF는 텍스트가 없어 실패로 안내), DOCX는 `word/document.xml`의 문단, TXT·MD는 그대로.
- Chunking(규칙 기반, 순수 함수): 문단 단위로 나누고, 제목(`#`, `제N장`, `제N조`, `N.`)을 만나면 현재 섹션 경로를 갱신한다. 문단을 모아 약 500자 청크를 만들고, 긴 문단은 문장 경계에서 자른다. 각 청크는 섹션 경로(예: `제3장 경비 > 제12조(접대비)`)와 PDF 페이지를 함께 갖는다.
- 임베딩: `intfloat/multilingual-e5-small`(384차원, 한국어 지원, MIT). e5 규칙대로 문서는 `passage: `, 질문은 `query: ` 접두어를 붙이고 정규화한다. 모델은 첫 실행 때 내려받아 볼륨에 캐시한다. 모델·차원은 문서마다 저장한다.
- 청크는 파생 데이터라 다시 색인하면 지우고 새로 만든다. 문서 자체는 지우지 않고 보관(ARCHIVED)한다.

## 3. 검색과 답변

- 질문 임베딩은 Spring이 ai-service `POST /v1/embed`를 동기 호출(시간 제한 10초).
- pgvector 코사인 거리로 `INDEXED` 문서의 청크 Top-5. 데이터가 작아 정확 검색(인덱스 없음)으로 하고, 회사 조건으로 걸러도 재현율이 떨어지지 않게 한다. 규모가 커지면 HNSW + 반복 스캔으로 바꾼다.
- 키워드 대체: 질문에서 2글자 이상 낱말을 뽑아 많이 포함한 청크 순.
- 관련도 기준: 유사도 0.83 미만인 발췌는 버리고, 1위보다 0.06 넘게 낮은 발췌도 근거에서 뺀다(설정 가능). 남는 발췌가 없으면 LLM을 부르지 않고 "등록된 규정에서 관련 내용을 찾지 못했습니다"(`NO_MATCH`).
  - 보정 근거(가상 규정 2개, 청크 17개): e5 유사도는 좁은 범위에 몰린다. 관련 질문의 1위 0.844~0.908(6개 모두 1위 정답), 무관한 질문의 1위 0.813~0.846. 점수만으로 완전히 가를 수 없어, 경계에 걸린 무관한 질문(예: 주차장 0.846)은 LLM이 "찾지 못했습니다"로 답하게 한다.
- 답변 facts: 질문 + 발췌 목록(`[번호] 문서명 › 섹션 (p.N)` + 본문). 프롬프트 `rag-v3`. 근거 번호는 구조화 출력의 `citations` 필드로도 받아, 본문에 [번호]가 없으면 끝에 붙인다.
- 검증: 숫자는 **발췌에 있는 것만**(질문의 숫자도 불가), 외국 문자, 인용 번호가 없거나 범위 밖이면 거부. "위반" 같은 표현은 발췌에 있으면 허용(규정 원문에 흔하다). 첫 문장이 "찾을 수 없다"인 답은 표준 문장으로 바꾸고 인용을 뗀다. 실패하면 발췌 목록 템플릿.
- 실제 qwen2.5 7B 출력에서 본 오류와 대응:
  - 맞는 답인데 본문 [번호]를 빠뜨림 → `citations` 필드(필수)로 받아 붙임
  - "주말 사용 불가" 규정을 "주말 사용 가능하지만…"으로 뒤집음 → 금지 규정을 다루는 예시를 프롬프트에 추가(이후 정답)
  - "300만원은 누가 승인?"에 질문 금액을 구간에 대입해 "팀장"이라고 틀림 → 질문의 숫자를 답에 못 쓰게 해(대입하면 거부) 원문 발췌로 대체하고, 프롬프트에 "구간을 그대로 안내"를 지시. 금액 비교·판정은 LLM에 맡기지 않는다는 원칙과 같다
  - "찾을 수 없습니다"를 제 표현으로 쓰고 [1]을 붙임 → 표준 문장으로 정규화
- 답변 수정·다시 생성은 Phase 5 구조 그대로(Audit).

## 4. Entity

- `rule_documents`: 회사, 제목, 분류, 파일(key·이름·형식·크기·SHA-256), 상태, 시도 번호, 청크 수, 임베딩 모델·차원, 오류, 올린 사람, 색인 시각, 보관자·시각·사유
- `rule_chunks`: 문서, 회사, 순번, 섹션, 페이지, 본문, `embedding vector(384)`
- `rule_questions`: 회사, 질문한 사람, 질문, 상태(`ANSWERED`·`NO_MATCH`), 검색 방식(`VECTOR`·`KEYWORD`), 검색 결과 스냅샷(청크 ID·문서·섹션·페이지·점수·본문)
- 답변: `ai_explanations`(대상 `RULE_ANSWER`, 대상 ID = 질문 ID)

## 5. API

| Method | Path | 권한 |
|---|---|---|
| POST | `/companies/{cid}/rules/documents` (multipart `file`, `title`, `category`) | ADMIN·ACCOUNTANT |
| GET | `/companies/{cid}/rules/documents` · `/{id}` · `/{id}/chunks` · `/{id}/file` | 멤버 |
| POST | `/companies/{cid}/rules/documents/{id}/reindex` | ADMIN·ACCOUNTANT |
| POST | `/companies/{cid}/rules/documents/{id}/archive` `{reason}` | ADMIN·ACCOUNTANT |
| POST | `/companies/{cid}/rules/questions` `{question}` | 멤버 |
| GET | `/companies/{cid}/rules/questions` · `/{id}` (본인 질문) | 멤버 |
| PUT | `…/questions/{id}/answer`, POST `…/{id}/answer/regenerate` | 본인 |
| POST | `/internal/v1/rules/documents/{id}/index` (`X-Internal-Token`) | ai-service |
| POST | (ai-service) `/v1/embed` `{texts, kind}` | backend |

Audit: `RULE_DOC_UPLOAD`, `RULE_DOC_INDEXED`, `RULE_DOC_FAILED`, `RULE_DOC_REINDEX`, `RULE_DOC_ARCHIVE`, `RULE_ASK`, `RULE_ANSWER_EDIT`, `RULE_ANSWER_REGENERATE`.

## 6. 화면

`회계 규정` 메뉴: 질문 입력과 답변(인용 번호를 누르면 해당 발췌로 이동), 근거 발췌 목록(문서·섹션·페이지·유사도), 규정 문서 목록·업로드·보관·다시 색인, 문서별 청크 미리보기. 가상 규정 샘플 문서를 함께 제공한다.
