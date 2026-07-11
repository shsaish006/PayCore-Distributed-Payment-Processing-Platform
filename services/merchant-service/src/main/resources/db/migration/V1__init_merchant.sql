-- Merchants Profile Table
CREATE TABLE merchants (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, SUSPENDED, PENDING_KYC
    webhook_url VARCHAR(512),
    webhook_secret VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Merchant API Keys (Stripe-like security: store hashed API keys)
CREATE TABLE merchant_api_keys (
    id VARCHAR(64) PRIMARY KEY,
    merchant_id VARCHAR(64) NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
    key_prefix VARCHAR(16) NOT NULL, -- e.g. "pk_live_" or "sk_live_"
    hashed_key VARCHAR(128) NOT NULL UNIQUE, -- SHA-256 hash of sk_live_...
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, REVOKED
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_merchant_api_keys_hash ON merchant_api_keys(hashed_key);
