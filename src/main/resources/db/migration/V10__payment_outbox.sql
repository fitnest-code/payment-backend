-- Transactional outbox for durable payment side-effects (Kafka + subscription assign).
CREATE TABLE IF NOT EXISTS payment_outbox (
    id               BIGSERIAL PRIMARY KEY,
    aggregate_id     VARCHAR(128),
    event_type       VARCHAR(64)  NOT NULL,
    topic            VARCHAR(128),
    payload          TEXT         NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempts         INT          NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_error       VARCHAR(1000),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at     TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_outbox_dispatch
    ON payment_outbox (status, next_attempt_at);

CREATE INDEX IF NOT EXISTS idx_outbox_aggregate
    ON payment_outbox (aggregate_id);

CREATE INDEX IF NOT EXISTS idx_outbox_created_at
    ON payment_outbox (created_at);
