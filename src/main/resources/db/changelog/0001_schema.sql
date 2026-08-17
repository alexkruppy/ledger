--liquibase formatted sql
--changeset alexkruppy:0001_schema

CREATE TABLE users (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email                 VARCHAR(255) NOT NULL,
    password_hash         VARCHAR(255) NOT NULL,
    first_name            VARCHAR(120),
    last_name             VARCHAR(120),
    role                  VARCHAR(16)  NOT NULL DEFAULT 'USER',
    status                VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    failed_login_attempts INTEGER      NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN', 'SYSTEM')),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'LOCKED'))
);

CREATE TABLE wallets (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users (id),
    currency   VARCHAR(3)   NOT NULL,
    balance    NUMERIC(28, 12) NOT NULL DEFAULT 0,
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    version    BIGINT       NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_wallets_user_currency UNIQUE (user_id, currency),
    CONSTRAINT ck_wallets_balance CHECK (balance >= 0),
    CONSTRAINT ck_wallets_currency CHECK (currency = upper(currency)),
    CONSTRAINT ck_wallets_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED'))
);

CREATE TABLE transfers (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    idempotency_key  VARCHAR(64)  NOT NULL,
    user_id          BIGINT       NOT NULL REFERENCES users (id),
    from_wallet_id   BIGINT       REFERENCES wallets (id),
    to_wallet_id     BIGINT       REFERENCES wallets (id),
    currency         VARCHAR(3)   NOT NULL,
    amount           NUMERIC(28, 12) NOT NULL,
    converted_amount NUMERIC(28, 12),
    fx_rate          NUMERIC(28, 12),
    fee              NUMERIC(28, 12) NOT NULL DEFAULT 0,
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    error_message    VARCHAR(1000),
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,
    CONSTRAINT uq_transfers_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_transfers_amount CHECK (amount > 0),
    CONSTRAINT ck_transfers_fee CHECK (fee >= 0),
    CONSTRAINT ck_transfers_currency CHECK (currency = upper(currency)),
    CONSTRAINT ck_transfers_status CHECK (status IN ('PENDING', 'COMPLETED', 'ROLLED_BACK', 'FAILED')),
    CONSTRAINT ck_transfers_has_side CHECK (from_wallet_id IS NOT NULL OR to_wallet_id IS NOT NULL)
);

CREATE INDEX idx_transfers_user ON transfers (user_id);
CREATE INDEX idx_transfers_status ON transfers (status, created_at);

CREATE TABLE payment_transactions (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    idempotency_key    VARCHAR(64)  NOT NULL,
    user_id            BIGINT       NOT NULL REFERENCES users (id),
    wallet_id          BIGINT       NOT NULL REFERENCES wallets (id),
    type               VARCHAR(16)  NOT NULL,
    amount             NUMERIC(28, 12) NOT NULL,
    currency           VARCHAR(3)   NOT NULL,
    status             VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    external_payment_id VARCHAR(64),
    failure_reason     VARCHAR(1000),
    attempts           INTEGER      NOT NULL DEFAULT 0,
    version            BIGINT       NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_payments_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_payments_amount CHECK (amount > 0),
    CONSTRAINT ck_payments_currency CHECK (currency = upper(currency)),
    CONSTRAINT ck_payments_type CHECK (type IN ('TOP_UP', 'WITHDRAW')),
    CONSTRAINT ck_payments_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'ROLLED_BACK'))
);

CREATE INDEX idx_payments_user ON payment_transactions (user_id);
CREATE INDEX idx_payments_status ON payment_transactions (status, attempts);

CREATE TABLE ledger_entries (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    wallet_id      BIGINT       NOT NULL REFERENCES wallets (id),
    transfer_id    BIGINT       REFERENCES transfers (id),
    payment_id     BIGINT       REFERENCES payment_transactions (id),
    entry_type     VARCHAR(8)   NOT NULL,
    amount         NUMERIC(28, 12) NOT NULL,
    balance_after  NUMERIC(28, 12) NOT NULL,
    currency       VARCHAR(3)   NOT NULL,
    description    VARCHAR(500),
    operation_key  VARCHAR(120) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_ledger_wallet_operation UNIQUE (wallet_id, operation_key),
    CONSTRAINT ck_ledger_type CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_amount CHECK (amount > 0),
    CONSTRAINT ck_ledger_currency CHECK (currency = upper(currency))
);

CREATE INDEX idx_ledger_wallet_created ON ledger_entries (wallet_id, created_at DESC);

CREATE TABLE exchange_rates (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    base_currency  VARCHAR(3)       NOT NULL,
    quote_currency VARCHAR(3)       NOT NULL,
    rate           NUMERIC(28, 12)  NOT NULL,
    updated_at     TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CONSTRAINT uq_exchange_rates_pair UNIQUE (base_currency, quote_currency),
    CONSTRAINT ck_exchange_rate CHECK (rate > 0)
);

CREATE TABLE outbox_events (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type VARCHAR(32)  NOT NULL,
    aggregate_id  VARCHAR(64)  NOT NULL,
    event_type    VARCHAR(64)  NOT NULL,
    payload       JSONB        NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempts      INTEGER      NOT NULL DEFAULT 0,
    available_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX idx_outbox_poll ON outbox_events (status, available_at, id) WHERE status = 'PENDING';
