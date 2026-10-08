-- LedgerGuard AI — Phase 4 Anomaly Detection

-- 회사별 탐지 기준. 행이 없으면 애플리케이션 기본값을 쓴다.
CREATE TABLE anomaly_settings (
    company_id            BIGINT PRIMARY KEY REFERENCES companies (id),
    approval_limit        NUMERIC(19, 2) NOT NULL CHECK (approval_limit > 0),
    near_limit_ratio      NUMERIC(3, 2)  NOT NULL CHECK (near_limit_ratio > 0 AND near_limit_ratio < 1),
    evidence_min_amount   NUMERIC(19, 2) NOT NULL CHECK (evidence_min_amount >= 0),
    new_vendor_min_amount NUMERIC(19, 2) NOT NULL CHECK (new_vendor_min_amount > 0),
    spike_multiplier      NUMERIC(5, 2)  NOT NULL CHECK (spike_multiplier > 1),
    night_start_hour      INT            NOT NULL CHECK (night_start_hour BETWEEN 0 AND 23),
    night_end_hour        INT            NOT NULL CHECK (night_end_hour BETWEEN 0 AND 23),
    ml_enabled            BOOLEAN        NOT NULL,
    updated_by            BIGINT         NOT NULL REFERENCES users (id),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- 분석 실행 이력. 그때의 설정과 ML 상태를 함께 남겨 결과를 재현·설명할 수 있게 한다.
CREATE TABLE anomaly_scans (
    id                BIGSERIAL PRIMARY KEY,
    company_id        BIGINT      NOT NULL REFERENCES companies (id),
    period_from       DATE        NOT NULL,
    period_to         DATE        NOT NULL,
    analyzed_count    INT         NOT NULL,
    flagged_count     INT         NOT NULL,
    summary           JSONB       NOT NULL,
    ml_status         VARCHAR(20) NOT NULL
        CHECK (ml_status IN ('APPLIED', 'PARTIAL', 'SKIPPED', 'UNAVAILABLE', 'DISABLED')),
    ml_info           JSONB       NOT NULL,
    settings_snapshot JSONB       NOT NULL,
    duration_ms       BIGINT      NOT NULL,
    created_by        BIGINT      NOT NULL REFERENCES users (id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (period_from <= period_to)
);
CREATE INDEX idx_as_company_created ON anomaly_scans (company_id, created_at DESC);

-- 이상거래 결과. 원천(거래 / 은행 거래)마다 한 행. 원천 ID는 두 테이블을 가리키므로 FK 대신 (유형, ID)로 둔다.
CREATE TABLE anomaly_results (
    id                BIGSERIAL PRIMARY KEY,
    company_id        BIGINT         NOT NULL REFERENCES companies (id),
    source_type       VARCHAR(20)    NOT NULL CHECK (source_type IN ('TRANSACTION', 'BANK_TRANSACTION')),
    source_id         BIGINT         NOT NULL,

    -- 원천 거래 스냅샷 (목록 표시용)
    tx_date           DATE           NOT NULL,
    tx_time           TIME,
    counterparty      VARCHAR(200),
    amount            NUMERIC(19, 2) NOT NULL CHECK (amount > 0),

    -- 판단 결과와 근거 (규칙·ML). 사람이 바꿀 수 있는 것은 adjusted_level뿐이다.
    risk_score        INT            NOT NULL CHECK (risk_score BETWEEN 0 AND 100),
    risk_level        VARCHAR(10)    NOT NULL CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    rule_score        INT            NOT NULL CHECK (rule_score BETWEEN 0 AND 100),
    ml_score          NUMERIC(4, 3) CHECK (ml_score BETWEEN 0 AND 1),
    rule_hits         JSONB          NOT NULL,
    features          JSONB          NOT NULL,
    ml_detail         JSONB          NOT NULL,
    context           JSONB          NOT NULL,

    -- 사람의 검토
    status            VARCHAR(20)    NOT NULL CHECK (status IN ('OPEN', 'CONFIRMED', 'DISMISSED', 'CLEARED')),
    adjusted_level    VARCHAR(10) CHECK (adjusted_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    effective_level   VARCHAR(10) GENERATED ALWAYS AS (COALESCE(adjusted_level, risk_level)) STORED,
    review_note       TEXT,
    reviewed_by       BIGINT REFERENCES users (id),
    reviewed_at       TIMESTAMPTZ,

    scan_id           BIGINT         NOT NULL REFERENCES anomaly_scans (id),
    first_detected_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),

    UNIQUE (company_id, source_type, source_id),
    -- 판정된 결과에는 검토자와 의견이 반드시 있다
    CHECK (status NOT IN ('CONFIRMED', 'DISMISSED') OR (reviewed_by IS NOT NULL AND review_note IS NOT NULL))
);
CREATE INDEX idx_ar_company_status ON anomaly_results (company_id, status, risk_score DESC);
CREATE INDEX idx_ar_company_date ON anomaly_results (company_id, tx_date);
