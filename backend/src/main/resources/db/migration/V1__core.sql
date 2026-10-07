-- LedgerGuard AI — Phase 1 Core Accounting

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    name          VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE companies (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    business_no VARCHAR(20),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE user_company_roles (
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    company_id BIGINT      NOT NULL REFERENCES companies (id),
    role       VARCHAR(20) NOT NULL CHECK (role IN ('ADMIN', 'ACCOUNTANT', 'VIEWER')),
    PRIMARY KEY (user_id, company_id)
);
CREATE INDEX idx_ucr_company ON user_company_roles (company_id);

CREATE TABLE accounts (
    id         BIGSERIAL PRIMARY KEY,
    company_id BIGINT       NOT NULL REFERENCES companies (id),
    code       VARCHAR(20)  NOT NULL,
    name       VARCHAR(100) NOT NULL,
    type       VARCHAR(20)  NOT NULL
        CHECK (type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    UNIQUE (company_id, code)
);

CREATE TABLE transactions (
    id           BIGSERIAL PRIMARY KEY,
    company_id   BIGINT         NOT NULL REFERENCES companies (id),
    tx_date      DATE           NOT NULL,
    counterparty VARCHAR(200),
    amount       NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    direction    VARCHAR(10)    NOT NULL CHECK (direction IN ('IN', 'OUT')),
    memo         TEXT,
    status       VARCHAR(20)    NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'VOID')),
    void_reason  TEXT,
    created_by   BIGINT         NOT NULL REFERENCES users (id),
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_tx_company_date ON transactions (company_id, tx_date);

CREATE TABLE journal_entries (
    id             BIGSERIAL PRIMARY KEY,
    company_id     BIGINT      NOT NULL REFERENCES companies (id),
    transaction_id BIGINT REFERENCES transactions (id),
    entry_date     DATE        NOT NULL,
    description    VARCHAR(500),
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'VOID')),
    void_reason    TEXT,
    created_by     BIGINT      NOT NULL REFERENCES users (id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_je_company_date ON journal_entries (company_id, entry_date);
CREATE INDEX idx_je_transaction ON journal_entries (transaction_id);

CREATE TABLE journal_entry_lines (
    id         BIGSERIAL PRIMARY KEY,
    entry_id   BIGINT         NOT NULL REFERENCES journal_entries (id) ON DELETE CASCADE,
    account_id BIGINT         NOT NULL REFERENCES accounts (id),
    debit      NUMERIC(19, 2) NOT NULL DEFAULT 0 CHECK (debit >= 0),
    credit     NUMERIC(19, 2) NOT NULL DEFAULT 0 CHECK (credit >= 0),
    -- 한 라인에는 차변 또는 대변 중 정확히 하나만 양수
    CHECK ((debit > 0) <> (credit > 0))
);
CREATE INDEX idx_jel_entry ON journal_entry_lines (entry_id);
CREATE INDEX idx_jel_account ON journal_entry_lines (account_id);

-- 차변 합계 = 대변 합계를 커밋 시점에 DB가 한 번 더 보장한다. (서비스 레이어 검증의 안전망)
CREATE FUNCTION check_journal_balanced() RETURNS trigger AS
$$
DECLARE
    eid BIGINT;
    d   NUMERIC;
    c   NUMERIC;
BEGIN
    IF TG_OP = 'DELETE' THEN
        eid := OLD.entry_id;
    ELSE
        eid := NEW.entry_id;
    END IF;

    SELECT COALESCE(SUM(debit), 0), COALESCE(SUM(credit), 0)
      INTO d, c
      FROM journal_entry_lines
     WHERE entry_id = eid;

    IF d <> c THEN
        RAISE EXCEPTION 'journal entry % is not balanced (debit=%, credit=%)', eid, d, c;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_journal_balanced
    AFTER INSERT OR UPDATE OR DELETE
    ON journal_entry_lines
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION check_journal_balanced();

CREATE TABLE audit_logs (
    id           BIGSERIAL PRIMARY KEY,
    company_id   BIGINT REFERENCES companies (id),
    actor_id     BIGINT REFERENCES users (id),
    action       VARCHAR(50) NOT NULL,
    target_type  VARCHAR(50) NOT NULL,
    target_id    BIGINT,
    before_value JSONB,
    after_value  JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_company_created ON audit_logs (company_id, created_at DESC);
CREATE INDEX idx_audit_target ON audit_logs (target_type, target_id);

-- 이력은 수정/삭제할 수 없다.
CREATE FUNCTION audit_logs_immutable() RETURNS trigger AS
$$
BEGIN
    RAISE EXCEPTION 'audit_logs is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_immutable
    BEFORE UPDATE OR DELETE
    ON audit_logs
    FOR EACH ROW
EXECUTE FUNCTION audit_logs_immutable();
