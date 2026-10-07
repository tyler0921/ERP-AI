-- LedgerGuard AI — Phase 3 Document AI (영수증·세금계산서)

CREATE TABLE documents (
    id                  BIGSERIAL PRIMARY KEY,
    company_id          BIGINT         NOT NULL REFERENCES companies (id),
    doc_type            VARCHAR(20)    NOT NULL CHECK (doc_type IN ('RECEIPT', 'TAX_INVOICE')),
    status              VARCHAR(20)    NOT NULL
        CHECK (status IN ('PENDING', 'AI_REVIEWED', 'REVIEW_REQUIRED', 'APPROVED', 'REJECTED', 'FAILED')),

    -- 원본 파일 (SeaweedFS)
    file_key            VARCHAR(200)   NOT NULL,
    file_name           VARCHAR(255)   NOT NULL,
    content_type        VARCHAR(50)    NOT NULL,
    file_size           BIGINT         NOT NULL,
    file_sha256         CHAR(64)       NOT NULL,

    -- 현재 필드 값 (AI 추출값으로 채워지고 사람이 수정할 수 있다)
    vendor_name         VARCHAR(200),
    vendor_business_no  VARCHAR(12),
    buyer_name          VARCHAR(200),
    buyer_business_no   VARCHAR(12),
    issue_date          DATE,
    supply_amount       NUMERIC(19, 2) CHECK (supply_amount >= 0),
    vat_amount          NUMERIC(19, 2) CHECK (vat_amount >= 0),
    total_amount        NUMERIC(19, 2) CHECK (total_amount >= 0),
    payment_method      VARCHAR(20),
    items_summary       VARCHAR(500),

    -- AI 원본 결과(OCR 줄, 필드별 confidence·근거)는 수정하지 않는다. 수정 내역은 Audit Log에 남는다.
    ai_result           JSONB,
    ai_confidence       NUMERIC(4, 3),
    validation          JSONB          NOT NULL DEFAULT '[]',
    recommendation      JSONB          NOT NULL DEFAULT '[]',
    recommended_account_id BIGINT REFERENCES accounts (id),

    -- 사람의 결정
    account_id          BIGINT REFERENCES accounts (id),
    journal_entry_id    BIGINT REFERENCES journal_entries (id),
    reviewed_by         BIGINT REFERENCES users (id),
    reviewed_at         TIMESTAMPTZ,
    review_note         TEXT,

    -- 비동기 처리 상태
    attempt             INT            NOT NULL DEFAULT 1,
    queued_at           TIMESTAMPTZ,
    processed_at        TIMESTAMPTZ,
    error_message       VARCHAR(500),

    uploaded_by         BIGINT         NOT NULL REFERENCES users (id),
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),

    -- 같은 회사에 같은 파일을 두 번 올릴 수 없다
    UNIQUE (company_id, file_sha256),
    -- 승인된 문서는 계정과목이 반드시 있어야 한다
    CHECK (status <> 'APPROVED' OR account_id IS NOT NULL)
);
CREATE INDEX idx_doc_company_status ON documents (company_id, status, created_at DESC);
CREATE INDEX idx_doc_vendor ON documents (company_id, vendor_business_no);
CREATE INDEX idx_doc_pending ON documents (status, queued_at) WHERE status = 'PENDING';
