-- LedgerGuard AI — Phase 2 Bank Reconciliation

-- 은행 계좌. 대사 시 전표의 "은행 금액"은 연결된 계정과목(gl_account_id) 라인으로 계산한다.
CREATE TABLE bank_accounts (
    id            BIGSERIAL PRIMARY KEY,
    company_id    BIGINT       NOT NULL REFERENCES companies (id),
    name          VARCHAR(100) NOT NULL,
    bank_name     VARCHAR(50),
    -- 개인정보 최소화: 끝 4자리만 저장한다.
    account_no_last4 VARCHAR(4),
    gl_account_id BIGINT       NOT NULL REFERENCES accounts (id),
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by    BIGINT       NOT NULL REFERENCES users (id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 계정과목 하나에 계좌 하나: 전표 금액을 어느 계좌에 귀속할지 모호하지 않게 한다.
    UNIQUE (company_id, gl_account_id)
);

CREATE TABLE bank_statement_imports (
    id              BIGSERIAL PRIMARY KEY,
    company_id      BIGINT       NOT NULL REFERENCES companies (id),
    bank_account_id BIGINT       NOT NULL REFERENCES bank_accounts (id),
    file_name       VARCHAR(255) NOT NULL,
    file_sha256     CHAR(64)     NOT NULL,
    encoding        VARCHAR(20)  NOT NULL,
    total_rows      INT          NOT NULL,
    imported_rows   INT          NOT NULL,
    duplicate_rows  INT          NOT NULL,
    created_by      BIGINT       NOT NULL REFERENCES users (id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_bsi_account ON bank_statement_imports (bank_account_id, created_at DESC);

-- 은행 거래 원천 데이터. 수정하지 않는다.
CREATE TABLE bank_transactions (
    id              BIGSERIAL PRIMARY KEY,
    company_id      BIGINT         NOT NULL REFERENCES companies (id),
    bank_account_id BIGINT         NOT NULL REFERENCES bank_accounts (id),
    import_id       BIGINT         NOT NULL REFERENCES bank_statement_imports (id),
    row_no          INT            NOT NULL,
    tx_date         DATE           NOT NULL,
    tx_time         TIME,
    direction       VARCHAR(10)    NOT NULL CHECK (direction IN ('IN', 'OUT')),
    amount          NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    balance         NUMERIC(19, 2),
    counterparty    VARCHAR(200),
    memo            VARCHAR(500),
    dedupe_key      CHAR(64)       NOT NULL,
    status          VARCHAR(20)    NOT NULL DEFAULT 'UNMATCHED'
        CHECK (status IN ('UNMATCHED', 'SUGGESTED', 'MATCHED')),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    UNIQUE (bank_account_id, dedupe_key)
);
CREATE INDEX idx_btx_account_date ON bank_transactions (company_id, bank_account_id, tx_date);
CREATE INDEX idx_btx_status ON bank_transactions (company_id, status);

-- 매칭 헤더. 1:1뿐 아니라 1:N, N:1을 표현할 수 있도록 참여자는 items로 분리한다.
CREATE TABLE reconciliation_matches (
    id           BIGSERIAL PRIMARY KEY,
    company_id   BIGINT      NOT NULL REFERENCES companies (id),
    match_type   VARCHAR(20) NOT NULL CHECK (match_type IN ('ONE_TO_ONE', 'ONE_TO_MANY', 'MANY_TO_ONE')),
    status       VARCHAR(20) NOT NULL CHECK (status IN ('SUGGESTED', 'CONFIRMED', 'REJECTED', 'CANCELLED')),
    source       VARCHAR(10) NOT NULL CHECK (source IN ('AUTO', 'MANUAL')),
    score        INT         NOT NULL CHECK (score BETWEEN 0 AND 100),
    -- 점수 근거 (항목별 점수, 날짜 차이, 유사도 등). 사람이 판단 근거를 확인할 수 있게 저장한다.
    score_detail JSONB       NOT NULL,
    created_by   BIGINT      NOT NULL REFERENCES users (id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_by  BIGINT REFERENCES users (id),
    reviewed_at  TIMESTAMPTZ,
    review_note  TEXT
);
CREATE INDEX idx_rm_company_status ON reconciliation_matches (company_id, status);

CREATE TABLE reconciliation_match_items (
    id                  BIGSERIAL PRIMARY KEY,
    match_id            BIGINT         NOT NULL REFERENCES reconciliation_matches (id),
    bank_transaction_id BIGINT REFERENCES bank_transactions (id),
    journal_entry_id    BIGINT REFERENCES journal_entries (id),
    amount              NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    -- 매칭이 CONFIRMED인 동안만 true. 아래 부분 유니크 인덱스의 기준이다.
    confirmed           BOOLEAN        NOT NULL DEFAULT FALSE,
    CHECK ((bank_transaction_id IS NULL) <> (journal_entry_id IS NULL))
);
CREATE INDEX idx_rmi_match ON reconciliation_match_items (match_id);
CREATE INDEX idx_rmi_bank_tx ON reconciliation_match_items (bank_transaction_id);
CREATE INDEX idx_rmi_entry ON reconciliation_match_items (journal_entry_id);

-- 은행 거래와 전표는 확정 매칭에 최대 한 번만 참여한다. (동시 승인 요청에도 DB가 보장)
CREATE UNIQUE INDEX ux_rmi_bank_tx_confirmed ON reconciliation_match_items (bank_transaction_id) WHERE confirmed;
CREATE UNIQUE INDEX ux_rmi_entry_confirmed ON reconciliation_match_items (journal_entry_id) WHERE confirmed;

-- 확정 매칭은 커밋 시점에 은행 금액 합계 = 전표 금액 합계여야 한다. (서비스 검증의 안전망)
CREATE FUNCTION check_reconciliation_balanced() RETURNS trigger AS
$$
DECLARE
    mid       BIGINT;
    bank_sum  NUMERIC;
    entry_sum NUMERIC;
BEGIN
    IF TG_OP = 'DELETE' THEN
        mid := OLD.match_id;
    ELSE
        mid := NEW.match_id;
    END IF;

    SELECT COALESCE(SUM(amount) FILTER (WHERE bank_transaction_id IS NOT NULL), 0),
           COALESCE(SUM(amount) FILTER (WHERE journal_entry_id IS NOT NULL), 0)
      INTO bank_sum, entry_sum
      FROM reconciliation_match_items
     WHERE match_id = mid
       AND confirmed;

    IF bank_sum <> entry_sum THEN
        RAISE EXCEPTION 'reconciliation match % is not balanced (bank=%, journal=%)', mid, bank_sum, entry_sum;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_reconciliation_balanced
    AFTER INSERT OR UPDATE OR DELETE
    ON reconciliation_match_items
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION check_reconciliation_balanced();
