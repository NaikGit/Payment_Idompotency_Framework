-- H2-compatible schema for payment framework JPA tests.
-- Mirrors database/oracle/V1__create_payment_tables.sql but uses H2 syntax.

CREATE TABLE IF NOT EXISTS processed_events (
    idempotency_key  VARCHAR(255)  NOT NULL PRIMARY KEY,
    state            VARCHAR(50)   NOT NULL,
    source           VARCHAR(255),
    destination      VARCHAR(255),
    received_at      TIMESTAMP     NOT NULL,
    completed_at     TIMESTAMP,
    processed_by     VARCHAR(255),
    retry_count      INT           DEFAULT 0,
    last_error       VARCHAR(2000),
    version          BIGINT        DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_processed_events_state       ON processed_events(state);
CREATE INDEX IF NOT EXISTS idx_processed_events_received_at ON processed_events(received_at);

CREATE TABLE IF NOT EXISTS outbox_entries (
    id               VARCHAR(36)   NOT NULL PRIMARY KEY,
    idempotency_key  VARCHAR(255)  NOT NULL,
    status           VARCHAR(50)   NOT NULL,
    destination      VARCHAR(255)  NOT NULL,
    http_method      VARCHAR(10),
    endpoint         VARCHAR(1000),
    payload          CLOB          NOT NULL,
    headers          CLOB,
    created_at       TIMESTAMP     NOT NULL,
    last_attempt_at  TIMESTAMP,
    delivered_at     TIMESTAMP,
    next_retry_at    TIMESTAMP,
    retry_count      INT           DEFAULT 0,
    last_error       VARCHAR(2000),
    last_http_status INT,
    locked_by        VARCHAR(255),
    lock_expires_at  TIMESTAMP,
    version          BIGINT        DEFAULT 0,
    CONSTRAINT fk_outbox_processed_event
        FOREIGN KEY (idempotency_key) REFERENCES processed_events(idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_outbox_status          ON outbox_entries(status);
CREATE INDEX IF NOT EXISTS idx_outbox_next_retry       ON outbox_entries(next_retry_at);
CREATE INDEX IF NOT EXISTS idx_outbox_idempotency_key  ON outbox_entries(idempotency_key);
CREATE INDEX IF NOT EXISTS idx_outbox_lock_expires     ON outbox_entries(lock_expires_at);
