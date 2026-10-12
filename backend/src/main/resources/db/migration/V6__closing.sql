-- LedgerGuard AI — Phase 6 Closing Copilot (월마감)

-- 회사·월마다 한 행. 집계는 매번 전표에서 계산하고, 마감 확정 때의 집계·체크리스트만 스냅샷으로 남긴다.
CREATE TABLE closing_months (
    id          BIGSERIAL PRIMARY KEY,
    company_id  BIGINT      NOT NULL REFERENCES companies (id),
    month       DATE        NOT NULL CHECK (EXTRACT(DAY FROM month) = 1),
    status      VARCHAR(10) NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    closed_by   BIGINT REFERENCES users (id),
    closed_at   TIMESTAMPTZ,
    close_note  TEXT,
    snapshot    JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (company_id, month),
    -- 확정된 월에는 확정자·의견·스냅샷이 반드시 있다
    CHECK (status <> 'CLOSED' OR (closed_by IS NOT NULL AND close_note IS NOT NULL AND snapshot IS NOT NULL))
);

-- LLM 설명 대상에 월마감 보고서를 추가한다
ALTER TABLE ai_explanations DROP CONSTRAINT ai_explanations_target_type_check;
ALTER TABLE ai_explanations ADD CONSTRAINT ai_explanations_target_type_check
    CHECK (target_type IN ('ANOMALY_RESULT', 'ANOMALY_SCAN', 'CLOSING_MONTH'));

-- 월별 집계용 (전표일 범위 + 상태)
CREATE INDEX idx_je_company_status_date ON journal_entries (company_id, status, entry_date);
