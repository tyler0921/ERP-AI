-- LedgerGuard AI — Phase 5 Local LLM 설명

-- LLM이 만든 설명. 대상(이상거래 결과 / 분석 요약)마다 현재 설명 한 행.
-- LLM에 준 입력(facts)을 그대로 남겨 무엇을 근거로 설명했는지 확인할 수 있게 한다. 사람의 수정 이력은 Audit Log에 남는다.
CREATE TABLE ai_explanations (
    id           BIGSERIAL PRIMARY KEY,
    company_id   BIGINT      NOT NULL REFERENCES companies (id),
    target_type  VARCHAR(20) NOT NULL CHECK (target_type IN ('ANOMALY_RESULT', 'ANOMALY_SCAN')),
    target_id    BIGINT      NOT NULL,
    status       VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'GENERATED', 'FALLBACK', 'FAILED', 'EDITED')),
    attempt      INT         NOT NULL DEFAULT 1,
    facts_hash   CHAR(64)    NOT NULL,
    facts        JSONB       NOT NULL,
    text         TEXT,
    checkpoints  JSONB       NOT NULL DEFAULT '[]',
    meta         JSONB       NOT NULL DEFAULT '{}',
    requested_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    edited_by    BIGINT REFERENCES users (id),
    edited_at    TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (target_type, target_id),
    -- 완료된 설명에는 본문이 있어야 하고, 사람이 수정한 설명에는 수정자가 있어야 한다
    CHECK (status NOT IN ('GENERATED', 'FALLBACK', 'EDITED') OR text IS NOT NULL),
    CHECK (status <> 'EDITED' OR edited_by IS NOT NULL)
);
CREATE INDEX idx_ae_company ON ai_explanations (company_id, target_type);
CREATE INDEX idx_ae_pending ON ai_explanations (status, requested_at) WHERE status = 'PENDING';
