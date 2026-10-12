-- LedgerGuard AI — Phase 7 Accounting AI Q&A (AI Copilot)

-- 자연어 질문 한 건. LLM이 고른 계획(plan)은 Spring이 검증한 뒤 저장하고, 미리 정의된 Query Tool로만 조회한다.
-- 조회 결과(result)는 질문 시점의 계산값이며 답변(ai_explanations.COPILOT_ANSWER)의 근거가 된다.
CREATE TABLE copilot_questions (
    id          BIGSERIAL PRIMARY KEY,
    company_id  BIGINT      NOT NULL REFERENCES companies (id),
    asked_by    BIGINT      NOT NULL REFERENCES users (id),
    question    TEXT        NOT NULL CHECK (char_length(question) BETWEEN 1 AND 500),
    status      VARCHAR(20) NOT NULL CHECK (status IN ('PLANNING', 'ANSWERED', 'UNSUPPORTED', 'FAILED')),
    attempt     INT         NOT NULL DEFAULT 1,
    plan        JSONB,
    plan_source VARCHAR(10) CHECK (plan_source IN ('LLM', 'RULE', 'USER')),
    plan_meta   JSONB       NOT NULL DEFAULT '{}',
    result      JSONB,
    error       TEXT,
    planned_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 조회까지 끝난 질문에는 계획과 결과가 있다
    CHECK (status NOT IN ('ANSWERED', 'UNSUPPORTED') OR (plan IS NOT NULL AND plan_source IS NOT NULL AND result IS NOT NULL))
);
CREATE INDEX idx_cq_asker ON copilot_questions (company_id, asked_by, id DESC);
CREATE INDEX idx_cq_planning ON copilot_questions (status, updated_at) WHERE status = 'PLANNING';

-- 답변은 LLM 설명 테이블을 그대로 쓴다
ALTER TABLE ai_explanations DROP CONSTRAINT ai_explanations_target_type_check;
ALTER TABLE ai_explanations ADD CONSTRAINT ai_explanations_target_type_check
    CHECK (target_type IN ('ANOMALY_RESULT', 'ANOMALY_SCAN', 'CLOSING_MONTH', 'COPILOT_ANSWER'));
