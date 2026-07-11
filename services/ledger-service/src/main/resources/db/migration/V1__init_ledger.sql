-- Double-Entry Ledger Accounts
CREATE TABLE accounts (
    id VARCHAR(64) PRIMARY KEY,
    merchant_id VARCHAR(64) NOT NULL,
    type VARCHAR(32) NOT NULL, -- ASSET (receivable), LIABILITY (payable), REVENUE, EXPENSE
    balance NUMERIC(18, 4) NOT NULL DEFAULT 0.0000,
    currency VARCHAR(3) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0, -- Optimistic locking
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_accounts_merchant_type_currency ON accounts(merchant_id, type, currency);

-- Ledger Entries (Parent transaction header)
CREATE TABLE ledger_entries (
    id VARCHAR(64) PRIMARY KEY,
    transaction_id VARCHAR(64) NOT NULL,
    description VARCHAR(255),
    type VARCHAR(32) NOT NULL, -- PAYMENT, REFUND, SETTLEMENT
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ledger_entries_transaction ON ledger_entries(transaction_id);

-- Journal Entries (Individual debit/credit lines)
CREATE TABLE journal_entries (
    id VARCHAR(64) PRIMARY KEY,
    ledger_entry_id VARCHAR(64) NOT NULL REFERENCES ledger_entries(id),
    account_id VARCHAR(64) NOT NULL REFERENCES accounts(id),
    entry_type VARCHAR(6) NOT NULL, -- DEBIT, CREDIT
    amount NUMERIC(18, 4) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_journal_entries_account ON journal_entries(account_id);

-- Inbox Pattern for consumer idempotency
CREATE TABLE inbox_messages (
    id VARCHAR(64) PRIMARY KEY, -- event_id or unique key
    consumer_group VARCHAR(128) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
