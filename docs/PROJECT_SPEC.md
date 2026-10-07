# 프로젝트명
LedgerGuard AI

# 프로젝트 개요
LedgerGuard AI는 영수증, 세금계산서, 은행 거래내역, 회계 전표 등의 데이터를 통합하여 증빙 인식, 자동 분류, 은행 거래 대사, 이상거래 탐지, 월마감 분석, 회계 데이터 질의를 지원하는 AI 기반 회계 업무 보조 플랫폼이다.

목표는 AI가 회계 업무를 완전히 대신하는 것이 아니라, 반복적인 검토 업무를 자동화하고 이상 가능성이 높은 거래를 우선적으로 식별하여 회계 담당자의 업무 효율성을 높이는 것이다.

모든 핵심 회계 판단은 가능한 한 규칙 기반 로직과 알고리즘으로 처리하고, LLM은 설명, 자연어 질의, 요약, 보조 판단 역할로 제한한다.

---

# 핵심 설계 원칙

1. 개인 프로젝트이므로 최대한 무료·오픈소스 기술을 사용한다.
2. OpenAI, Claude 등 유료 LLM API를 필수 의존성으로 사용하지 않는다.
3. 로컬 LLM은 Ollama를 사용한다.
4. OCR은 PaddleOCR을 사용한다.
5. 메인 백엔드는 Spring Boot로 구성한다.
6. AI 및 ML 처리는 FastAPI 기반 Python 서버로 분리한다.
7. 데이터베이스는 PostgreSQL을 사용한다.
8. Vector DB를 별도로 사용하지 않고 PostgreSQL + pgvector를 사용한다.
9. 캐시 및 비동기 작업 처리는 Valkey를 사용한다.
10. 증빙 파일 저장은 SeaweedFS를 사용한다.
11. 전체 개발환경은 Docker Compose로 실행 가능하도록 구성한다.
12. AI의 판단 결과는 사람이 확인하고 승인할 수 있도록 Human-in-the-loop 구조를 적용한다.
13. 모든 주요 처리 결과와 승인 이력은 Audit Log로 남긴다.

---

# 전체 사용자 흐름

사용자가 회사 또는 조직을 등록한다.

↓

은행 거래내역, 영수증, 세금계산서, 전표 데이터를 등록한다.

↓

영수증 및 세금계산서의 경우 OCR을 통해 데이터를 추출한다.

↓

추출된 거래정보를 정규화한다.

↓

계정과목을 추천한다.

↓

은행 거래와 회계 전표를 자동으로 대사한다.

↓

모든 거래에 대해 규칙 기반 검증과 이상거래 탐지를 수행한다.

↓

이상 가능성이 높은 거래에는 Risk Score를 부여한다.

↓

LLM이 이상 판단의 이유를 사람이 이해할 수 있는 형태로 설명한다.

↓

회계 담당자가 결과를 승인하거나 수정한다.

↓

확정된 데이터를 기반으로 월마감 분석을 수행한다.

↓

사용자는 자연어로 회계 데이터에 대해 질문할 수 있다.

---

# 주요 기능

## 1. 사용자 및 회사 관리

기능:
- 회원가입
- 로그인
- JWT 인증
- 회사 등록
- 사용자별 회사 접근 권한
- 관리자 / 회계담당자 등 Role 관리

주요 엔티티:
- User
- Company
- UserCompanyRole

---

# 2. 거래 및 전표 관리

기능:
- 거래 등록
- 거래 조회
- 전표 등록
- 전표 수정
- 전표 삭제
- 계정과목 관리
- 거래와 전표 연결

주요 엔티티:
- Transaction
- JournalEntry
- JournalEntryLine
- Account

---

# 3. 영수증 자동 인식

사용자가 이미지 또는 PDF 영수증을 업로드한다.

처리 과정:

영수증 업로드
→ 파일 저장
→ PaddleOCR
→ 텍스트 추출
→ 거래정보 추출
→ 정규화
→ 계정과목 추천
→ 사용자 검토
→ 거래 및 전표 연결

주요 추출 데이터:
- 거래처
- 거래일
- 총 금액
- 부가세
- 결제수단
- 사업자등록번호
- 상품 또는 서비스 내용

결과에는 Confidence Score를 포함한다.

---

# 4. 세금계산서 OCR 및 검증

세금계산서 PDF 또는 이미지를 분석한다.

추출 데이터:
- 공급자
- 공급받는자
- 사업자등록번호
- 작성일
- 공급가액
- 부가세
- 합계금액

검증 예시:

공급가액 + 부가세 = 합계금액

불일치 시 Validation Error로 등록한다.

LLM은 계산 자체를 수행하는 것이 아니라 검증 결과를 설명하는 역할만 담당한다.

---

# 5. Bank Reconciliation

서비스의 핵심 기능 중 하나이다.

은행 거래내역과 회계 전표를 자동으로 매칭한다.

사용자는 CSV 등의 형태로 은행 거래내역을 업로드할 수 있다.

Matching 기준 예시:

- 금액
- 거래일
- 거래처명
- 입출금 구분
- 거래 메모
- 과거 거래 패턴

각 후보에 Match Score 0~100을 부여한다.

예시:

금액 일치: 40점  
날짜 유사도: 20점  
거래처 유사도: 20점  
메모 유사도: 10점  
과거 패턴: 10점

총점이 일정 기준 이상이면 자동매칭 후보로 표시한다.

예:

90점 이상:
높은 매칭 가능성

70~89점:
담당자 검토 필요

70점 미만:
미매칭

단순 1:1 매칭뿐 아니라 다음 형태도 지원 가능하도록 설계한다.

- 1:1
- 1:N
- N:1

예:

은행 출금 1,253,000원

↓

매입대금 1,250,000원
+
송금수수료 3,000원

↓

합계 일치

↓

매칭 후보

---

# 6. 이상거래 탐지

이상거래 탐지는 두 개의 엔진을 결합한다.

## Rule Engine

예시 규칙:
- 동일 금액 중복 결제
- 동일 거래처 반복 결제
- 승인 기준 직전 금액 반복
- 짧은 시간 내 쪼개기 결제
- 주말 또는 심야 결제
- 증빙 없는 거래
- 최근 평균보다 지나치게 큰 거래
- 신규 거래처 고액 거래
- 특정 계정과목 급증
- 동일 영수증 중복 등록

## ML 기반 이상탐지

Python scikit-learn의 IsolationForest 등을 사용한다.

Feature 예시:
- amount
- transactionHour
- weekday
- merchantFrequency
- averageAmount
- amountRatioToAverage
- monthlyTransactionCount
- accountCategory
- daysSinceLastTransaction

Rule Score와 ML Score를 조합하여 Risk Score 0~100을 생성한다.

Risk Level 예시:

0~39:
LOW

40~69:
MEDIUM

70~84:
HIGH

85~100:
CRITICAL

---

# 7. AI 이상거래 설명

이상거래 탐지 결과를 Ollama 기반 Local LLM이 설명한다.

LLM이 직접 이상 여부를 결정하지 않는다.

입력 예:

거래금액:
4,950,000원

최근 평균:
650,000원

동일일 거래:
4,800,000원

승인 기준:
5,000,000원

Risk Score:
89

LLM 출력 예:

"해당 거래는 최근 6개월 평균 거래금액보다 약 7.6배 높으며, 같은 날 동일 거래처에서 추가 거래가 발생했습니다. 또한 회사 승인 기준인 500만원 직전 금액으로 결제되어 분할 결제 여부를 확인할 필요가 있습니다."

---

# 8. 월마감 Copilot

월별 회계 데이터를 분석한다.

주요 분석:
- 매출 증감
- 비용 증감
- 영업이익 변화
- 계정과목별 변화
- 거래처별 비용 변화
- 부서별 비용 변화
- 전월 대비
- 전년 동월 대비
- 이상 거래 현황

LLM에게 모든 원본 데이터를 직접 전달하지 않는다.

DB에서 먼저 계산한 Aggregate 결과를 LLM에 전달한다.

예:

전월 영업이익:
84,000,000원

당월 영업이익:
51,000,000원

광고비 증가:
21,000,000원

외주비 증가:
8,000,000원

운송비 증가:
4,000,000원

LLM은 이를 기반으로 자연어 설명을 생성한다.

---

# 9. 회계 AI 챗봇

사용자는 자연어로 자신의 회계 데이터를 질의할 수 있다.

예:

"이번 달 광고비가 얼마나 증가했어?"

"가장 비용이 많이 증가한 거래처를 알려줘."

"이번 달 고위험 거래만 보여줘."

"3분기 접대비가 가장 많은 부서는 어디야?"

처리 구조:

사용자 질문
→ LLM Query Planning
→ 허용된 API 또는 SQL Query 생성
→ 데이터 조회
→ 결과 검증
→ LLM 자연어 답변

보안상 LLM이 DB에 자유롭게 SQL을 실행하지 못하도록 제한한다.

가능하면 Repository/API 레이어 또는 사전에 정의된 Query Tool을 사용한다.

---

# 10. 회계 규정 RAG

회사 내부 회계 규정 또는 계정과목 가이드를 등록할 수 있다.

예:
- 회사 비용 처리 규정
- 법인카드 사용 규정
- 계정과목 가이드
- 과거 회계 처리 사례
- 내부 승인 규정

처리:

문서
→ Chunking
→ sentence-transformers Embedding
→ pgvector 저장

질문 발생 시:

질문
→ Embedding
→ pgvector 검색
→ 관련 문서 Top-K
→ Ollama Local LLM
→ 근거 기반 답변

답변에는 참조한 규정 또는 문서 근거를 표시한다.

---

# 11. 승인 Workflow

AI 결과는 최종 확정값으로 바로 처리하지 않는다.

처리 상태 예:

PENDING

↓

AI_REVIEWED

↓

REVIEW_REQUIRED

↓

APPROVED

또는

REJECTED

회계 담당자는 다음 작업을 수행할 수 있다.

- AI 추천 승인
- AI 추천 거절
- 계정과목 수정
- 거래 매칭 수정
- Risk Level 조정
- 검토 의견 입력

---

# 12. Audit Log

모든 주요 작업을 기록한다.

예:

- 누가
- 언제
- 무엇을
- 어떤 값에서
- 어떤 값으로 변경했는지

예:

2026-10-08 14:32

User:
accountant01

Action:
ACCOUNT_CHANGE

Before:
소모품비

After:
접대비

Target:
JournalEntry #1023

이력은 삭제하지 않는다.

---

# Frontend 구성

Next.js를 사용한다.

주요 화면:

## Login
로그인

## Dashboard
전체 회계 현황

표시 정보:
- 이번 달 거래 건수
- 처리되지 않은 증빙
- 자동대사 성공률
- 미매칭 거래 수
- 이상거래 수
- 고위험 거래
- 월마감 진행률

## Transactions
거래 목록

## Journal Entries
전표 목록

## Documents
영수증 / 세금계산서

## Reconciliation
은행 거래 ↔ 전표 대사

## Anomaly Detection
이상거래 관리

## Closing
월마감 분석

## AI Copilot
회계 데이터 자연어 질의

## Accounting Rules
RAG 문서 관리

## Audit Log
변경 이력

## Settings
회사 및 사용자 관리

---

# 기술 스택

## Frontend

Next.js  
TypeScript

필요 시:
Tailwind CSS

---

## Main Backend

Java  
Spring Boot

담당 영역:
- 인증
- 사용자
- 회사
- 거래
- 전표
- 계정과목
- 승인
- Bank Reconciliation
- Audit Log
- API

---

## AI Backend

Python  
FastAPI

담당 영역:
- OCR
- Document Parsing
- Account Classification
- 이상거래 ML
- Embedding
- RAG
- Local LLM 연동
- AI Explanation

---

## Database

PostgreSQL

Extension:
pgvector

---

## Cache / Queue

Valkey

용도:
- Cache
- OCR 작업
- AI 작업
- 비동기 처리 상태 관리

---

## File Storage

SeaweedFS

저장 대상:
- 영수증
- 세금계산서
- 첨부파일
- 회계 규정 문서

---

## OCR

PaddleOCR

---

## Machine Learning

Python

scikit-learn

주요 알고리즘:
IsolationForest

추후 다른 이상탐지 모델과 비교 가능하도록 인터페이스 분리

---

## Embedding

sentence-transformers

---

## LLM

Ollama

무료 Local LLM 사용

후보:
- Qwen
- Llama
- Mistral

특정 모델에 강하게 결합하지 않도록 LLM Provider Interface를 설계한다.

---

# 전체 아키텍처

Frontend

Next.js

↓

Spring Boot API

↓

--------------------------------

PostgreSQL + pgvector

Valkey

SeaweedFS

--------------------------------

↓

FastAPI AI Service

↓

PaddleOCR

scikit-learn

sentence-transformers

Ollama

---

# 서비스 책임 분리

## Spring Boot

다음과 같은 비즈니스 로직 담당:

- Authentication
- User
- Company
- Transaction
- Journal
- Account
- Approval
- Reconciliation
- Audit Log
- AI 요청 관리

---

## FastAPI

AI 관련 기능 담당:

- OCR
- Document Parsing
- Classification
- Anomaly Detection
- Embedding
- RAG
- LLM
- AI Explanation

---

# LLM 사용 원칙

LLM에 다음 작업을 맡기지 않는다.

- 회계 금액 계산
- 세금 계산
- 데이터 정합성 검증
- 최종 거래 승인
- 이상거래 최종 판정
- DB 직접 수정

LLM은 다음 작업에 사용한다.

- 이상거래 설명
- 자연어 질문 이해
- Query Planning
- 월마감 설명
- 회계 규정 RAG 답변
- 데이터 요약
- 계정과목 추천 보조

---

# 개발 우선순위

## Phase 1 — Core Accounting

먼저 AI 없이 서비스 기본 구조를 만든다.

구현:

- Spring Boot 프로젝트
- Next.js 프로젝트
- PostgreSQL
- 회원가입
- 로그인
- 회사 관리
- 계정과목
- 거래
- 전표
- Audit Log 기본 구조

목표:

회계 데이터를 정상적으로 등록하고 조회할 수 있는 상태

---

# Phase 2 — Bank Reconciliation

구현:

- 은행 CSV 업로드
- 거래 데이터 정규화
- 전표 매칭 후보 검색
- Match Score
- 1:1 Matching
- 수동 승인

추후:
- 1:N
- N:1

---

# Phase 3 — Document AI

구현:

- SeaweedFS
- 영수증 업로드
- 세금계산서 업로드
- PaddleOCR
- 필드 추출
- Validation
- 계정과목 추천
- 전표 연결

---

# Phase 4 — Anomaly Detection

구현:

- Rule Engine
- IsolationForest
- Risk Score
- Risk Level
- 이상거래 Dashboard
- 사용자 검토

---

# Phase 5 — Local LLM

구현:

- Ollama
- FastAPI ↔ Ollama
- 이상거래 설명
- 결과 Summary

---

# Phase 6 — Closing Copilot

구현:

- 월별 Aggregate API
- 전월 비교
- 전년 동월 비교
- 주요 변동 탐지
- LLM 설명

---

# Phase 7 — Accounting AI Q&A

구현:

- 자연어 질문
- Query Planner
- 안전한 Query Tool
- 결과 조회
- 자연어 설명

---

# Phase 8 — Accounting RAG

구현:

- 회계 규정 문서 업로드
- Parsing
- Chunking
- Embedding
- pgvector
- Retrieval
- LLM 답변
- 출처 표시

---

# MVP 범위

최초 MVP에서는 다음 기능만 완성한다.

1. 로그인
2. 회사 등록
3. 계정과목 관리
4. 거래 관리
5. 전표 관리
6. 은행 CSV 업로드
7. Bank Reconciliation
8. Match Score
9. 사용자 승인
10. 기본 Dashboard

MVP 완성 이후 AI 기능을 순차적으로 추가한다.

---

# 최종 서비스 처리 흐름

영수증 / 세금계산서 / 은행거래 / 전표

↓

데이터 수집

↓

OCR

↓

데이터 정규화

↓

계정과목 추천

↓

Bank Reconciliation

↓

Rule Validation

↓

ML Anomaly Detection

↓

Risk Score

↓

LLM Explanation

↓

Human Review

↓

Approval

↓

Accounting Data

↓

Monthly Closing Analysis

↓

Accounting AI Copilot

---

# 프로젝트의 핵심 차별점

이 프로젝트는 단순한 회계 챗봇이 아니다.

OCR, 회계 데이터 처리, 자동대사, Rule Engine, Machine Learning, RAG, LLM을 하나의 회계 Workflow 안에서 연결하는 것이 핵심이다.

특히 다음 구조를 강조한다.

OCR
→ 데이터 정규화
→ 자동대사
→ 이상탐지
→ AI 설명
→ Human Approval
→ Audit Log

AI가 모든 결정을 대신하는 구조가 아니라,

"알고리즘과 규칙이 판단하고, AI가 설명하며, 사람이 최종 승인하는 구조"

를 지향한다.

---

# 개발 시 반드시 지킬 사항

- 처음부터 모든 기능을 구현하지 않는다.
- Phase 단위로 완성한다.
- 각 Phase 완료 시 테스트를 작성한다.
- AI 기능보다 회계 데이터 모델과 비즈니스 로직을 먼저 완성한다.
- Frontend / Backend / AI Server의 책임을 명확하게 분리한다.
- 특정 LLM에 종속되지 않도록 한다.
- 모든 AI 결과에는 confidence 또는 근거 정보를 함께 저장한다.
- AI의 결과를 사용자가 수정할 수 있도록 한다.
- 수정 내역은 Audit Log에 기록한다.
- 실서비스 금융 데이터가 아닌 가상 데이터를 사용해 개발한다.
- API Key가 필요한 유료 서비스는 기본 구조에서 제외한다.
- Docker Compose 하나로 전체 개발환경을 실행할 수 있도록 한다.

---

# AI 개발 지원 요청 방식

앞으로 이 프로젝트를 개발할 때 위 설계를 기본 요구사항으로 사용한다.

코드를 한 번에 전체 생성하지 말고 Phase별로 개발한다.

각 단계에서 다음 순서를 따른다.

1. 현재 Phase 목표 정의
2. 필요한 Entity 정의
3. DB Schema 설계
4. API 설계
5. Backend 구현
6. 테스트 작성
7. Frontend 구현
8. 필요한 경우 AI 기능 구현
9. 테스트 및 리팩토링
10. 다음 Phase 진행

설계를 변경해야 하는 경우 기존 아키텍처와 충돌 여부를 먼저 검토한다.

기능 구현을 요청받으면 단순 코드만 작성하지 말고 다음 항목도 함께 고려한다.

- 책임 분리
- 확장성
- 예외처리
- Validation
- Transaction 처리
- 보안
- 테스트 가능성
- Audit Log
- AI 실패 처리
- 데이터 정합성

이 프로젝트를 취업 포트폴리오 수준의 실제 서비스처럼 설계하고 구현한다.