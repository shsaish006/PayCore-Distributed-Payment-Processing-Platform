-- Create partitioned parent payments table
CREATE TABLE payments (
    id VARCHAR(64) NOT NULL,
    merchant_id VARCHAR(64) NOT NULL,
    amount NUMERIC(18, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    payment_method VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    card_token VARCHAR(128),
    gateway_reference VARCHAR(128),
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

-- Pre-create partitions for July 2026, August 2026, and a default partition
CREATE TABLE payments_y2026m07 PARTITION OF payments
    FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-08-01 00:00:00+00');

CREATE TABLE payments_y2026m08 PARTITION OF payments
    FOR VALUES FROM ('2026-08-01 00:00:00+00') TO ('2026-09-01 00:00:00+00');

-- Index for idempotency checks
CREATE UNIQUE INDEX idx_payments_idem_merchant ON payments (merchant_id, idempotency_key, created_at);

-- Saga Logs for distributed transactions
CREATE TABLE payment_sagas (
    id VARCHAR(64) PRIMARY KEY,
    payment_id VARCHAR(64) NOT NULL,
    merchant_id VARCHAR(64) NOT NULL,
    amount NUMERIC(18, 4) NOT NULL,
    status VARCHAR(32) NOT NULL, -- IN_PROGRESS, COMPLETED, COMPENSATED, FAILED
    current_step VARCHAR(32) NOT NULL, -- FRAUD_CHECK, AUTHORIZE, LEDGER_RECORD, CAPTURE, DONE
    payload TEXT NOT NULL, -- JSON dump of saga state context
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_payment_sagas_payment ON payment_sagas(payment_id);

-- Transactional Outbox for exactly-once publishing to Kafka
CREATE TABLE outbox_events (
    id VARCHAR(64) PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING, PROCESSED, FAILED
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_outbox_events_status ON outbox_events(status);
