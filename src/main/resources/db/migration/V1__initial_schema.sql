-- Initial banking schema for H2 and PostgreSQL

CREATE TABLE IF NOT EXISTS accounts (
  account_id VARCHAR(64) NOT NULL,
  opened_at TIMESTAMP NOT NULL,
  PRIMARY KEY (account_id)
);

CREATE TABLE IF NOT EXISTS ledger_transactions (
  transaction_id VARCHAR(36) NOT NULL,
  operation VARCHAR(32) NOT NULL,
  occurred_at TIMESTAMP NOT NULL,
  reverses_transaction_id VARCHAR(36),
  adjusts_transaction_id VARCHAR(36),
  sequence_no BIGSERIAL,
  PRIMARY KEY (transaction_id),
  UNIQUE (reverses_transaction_id)
);

CREATE TABLE IF NOT EXISTS ledger_entries (
  transaction_id VARCHAR(36) NOT NULL,
  entry_index INT NOT NULL,
  account VARCHAR(64) NOT NULL,
  customer_account_id VARCHAR(64),
  direction VARCHAR(8) NOT NULL,
  amount NUMERIC(19,6) NOT NULL,
  currency VARCHAR(8) NOT NULL,
  PRIMARY KEY (transaction_id, entry_index),
  FOREIGN KEY (transaction_id) REFERENCES ledger_transactions (transaction_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS balance_adjustments (
  transaction_id VARCHAR(36) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  actor VARCHAR(128) NOT NULL,
  PRIMARY KEY (transaction_id),
  FOREIGN KEY (transaction_id) REFERENCES ledger_transactions (transaction_id)
);

CREATE TABLE IF NOT EXISTS audit_log (
  audit_id BIGSERIAL,
  transaction_id VARCHAR(36) NOT NULL,
  operation VARCHAR(32) NOT NULL,
  account_id VARCHAR(64) NOT NULL,
  amount NUMERIC(19,6) NOT NULL,
  resulting_balance NUMERIC(19,6) NOT NULL,
  occurred_at TIMESTAMP NOT NULL,
  PRIMARY KEY (audit_id)
);

CREATE TABLE IF NOT EXISTS bill_payment_inquiries (
  inquiry_id VARCHAR(36) NOT NULL,
  account_id VARCHAR(64) NOT NULL,
  biller_code VARCHAR(64) NOT NULL,
  reference_code1 VARCHAR(128) NOT NULL,
  reference_code2 VARCHAR(128) NOT NULL,
  amount NUMERIC(19,6) NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  status VARCHAR(16) NOT NULL,
  PRIMARY KEY (inquiry_id)
);

CREATE TABLE IF NOT EXISTS bill_payment_processes (
  payment_id VARCHAR(36) NOT NULL,
  inquiry_id VARCHAR(36) NOT NULL,
  account_id VARCHAR(64) NOT NULL,
  biller_code VARCHAR(64) NOT NULL,
  reference_code1 VARCHAR(128) NOT NULL,
  reference_code2 VARCHAR(128) NOT NULL,
  amount NUMERIC(19,6) NOT NULL,
  status VARCHAR(32) NOT NULL,
  resulting_balance NUMERIC(19,6),
  receipt_code VARCHAR(255),
  receipt_paid_at TIMESTAMP,
  failure_code VARCHAR(64),
  failure_message VARCHAR(512),
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  PRIMARY KEY (payment_id)
);

CREATE TABLE IF NOT EXISTS interest_rate_history (
  rate_id BIGSERIAL,
  rate_basis_points BIGINT NOT NULL,
  effective_from DATE NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_by VARCHAR(256) NOT NULL,
  PRIMARY KEY (rate_id)
);

CREATE TABLE IF NOT EXISTS interest_processing_state (
  state_id INT NOT NULL,
  next_period_start DATE NOT NULL,
  PRIMARY KEY (state_id)
);
