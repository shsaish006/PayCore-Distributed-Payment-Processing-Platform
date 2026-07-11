-- Webhook Configurations (cached in Redis, backed by DB)
CREATE TABLE webhook_configs (
    id VARCHAR(64) PRIMARY KEY,
    merchant_id VARCHAR(64) NOT NULL,
    url VARCHAR(512) NOT NULL,
    secret VARCHAR(128) NOT NULL,
    events TEXT[] NOT NULL, -- e.g. {"payment.authorized", "payment.captured"}
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, INACTIVE
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_webhook_configs_merchant ON webhook_configs(merchant_id);

-- Webhook Delivery Attempts
CREATE TABLE webhook_deliveries (
    id VARCHAR(64) PRIMARY KEY,
    merchant_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    url VARCHAR(512) NOT NULL,
    status VARCHAR(32) NOT NULL, -- SUCCESS, RETRYING, FAILED
    attempts INT NOT NULL DEFAULT 0,
    last_response_code INT,
    last_response_body TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    next_retry_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_webhook_deliveries_status ON webhook_deliveries(status);
CREATE INDEX idx_webhook_deliveries_retry ON webhook_deliveries(next_retry_at) WHERE status = 'RETRYING';
