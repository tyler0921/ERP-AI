-- LedgerGuard AI — Phase 8 Accounting RAG (회계 규정)

CREATE EXTENSION IF NOT EXISTS vector;

-- 회계 규정 문서. 원본은 SeaweedFS에 두고, 보관(ARCHIVED)은 사유와 함께 남긴다(물리 삭제 없음).
CREATE TABLE rule_documents (
    id              BIGSERIAL PRIMARY KEY,
    company_id      BIGINT       NOT NULL REFERENCES companies (id),
    title           VARCHAR(200) NOT NULL,
    category        VARCHAR(30)  NOT NULL
        CHECK (category IN ('EXPENSE_POLICY', 'CORPORATE_CARD', 'ACCOUNT_GUIDE', 'CASE', 'APPROVAL', 'OTHER')),
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'INDEXED', 'FAILED', 'ARCHIVED')),
    file_key        VARCHAR(300) NOT NULL UNIQUE,
    file_name       VARCHAR(255) NOT NULL,
    content_type    VARCHAR(100) NOT NULL,
    file_size       BIGINT       NOT NULL CHECK (file_size > 0),
    file_sha256     CHAR(64)     NOT NULL,
    attempt         INT          NOT NULL DEFAULT 1,
    queued_at       TIMESTAMPTZ  NOT NULL,
    chunk_count     INT          NOT NULL DEFAULT 0,
    char_count      INT          NOT NULL DEFAULT 0,
    embedding_model VARCHAR(100),
    embedding_dims  INT,
    error           TEXT,
    indexed_at      TIMESTAMPTZ,
    uploaded_by     BIGINT       NOT NULL REFERENCES users (id),
    archived_by     BIGINT REFERENCES users (id),
    archived_at     TIMESTAMPTZ,
    archive_reason  TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (company_id, file_sha256),
    CHECK (status <> 'ARCHIVED' OR (archived_by IS NOT NULL AND archive_reason IS NOT NULL)),
    CHECK (status <> 'INDEXED' OR (embedding_model IS NOT NULL AND embedding_dims IS NOT NULL AND indexed_at IS NOT NULL))
);
CREATE INDEX idx_rd_company ON rule_documents (company_id, status);
CREATE INDEX idx_rd_pending ON rule_documents (status, queued_at) WHERE status = 'PENDING';

-- 청크(파생 데이터). 다시 색인하면 지우고 새로 만든다. 회사 ID를 함께 두어 검색 SQL이 항상 회사 조건을 갖게 한다.
-- 임베딩: intfloat/multilingual-e5-small (384차원). 데이터가 작아 정확 검색(ANN 인덱스 없음)을 쓴다.
CREATE TABLE rule_chunks (
    id          BIGSERIAL PRIMARY KEY,
    document_id BIGINT      NOT NULL REFERENCES rule_documents (id),
    company_id  BIGINT      NOT NULL REFERENCES companies (id),
    chunk_index INT         NOT NULL CHECK (chunk_index >= 0),
    section     VARCHAR(500),
    page        INT,
    content     TEXT        NOT NULL,
    embedding   vector(384) NOT NULL,
    UNIQUE (document_id, chunk_index)
);
CREATE INDEX idx_rc_company_doc ON rule_chunks (company_id, document_id);

-- 규정 질문. 검색 결과(sources)는 질문 시점의 스냅샷이라 문서가 바뀌어도 답변 근거를 다시 볼 수 있다.
CREATE TABLE rule_questions (
    id             BIGSERIAL PRIMARY KEY,
    company_id     BIGINT      NOT NULL REFERENCES companies (id),
    asked_by       BIGINT      NOT NULL REFERENCES users (id),
    question       TEXT        NOT NULL CHECK (char_length(question) BETWEEN 1 AND 500),
    status         VARCHAR(20) NOT NULL CHECK (status IN ('ANSWERED', 'NO_MATCH')),
    retrieval_mode VARCHAR(10) NOT NULL CHECK (retrieval_mode IN ('VECTOR', 'KEYWORD')),
    retrieval_meta JSONB       NOT NULL DEFAULT '{}',
    sources        JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_rq_asker ON rule_questions (company_id, asked_by, id DESC);

ALTER TABLE ai_explanations DROP CONSTRAINT ai_explanations_target_type_check;
ALTER TABLE ai_explanations ADD CONSTRAINT ai_explanations_target_type_check
    CHECK (target_type IN ('ANOMALY_RESULT', 'ANOMALY_SCAN', 'CLOSING_MONTH', 'COPILOT_ANSWER', 'RULE_ANSWER'));
