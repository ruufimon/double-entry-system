-- One interest credit per account per month, enforced alongside the ledger posting

CREATE TABLE IF NOT EXISTS interest_credit_periods (
  account_id VARCHAR(64) NOT NULL,
  period_start DATE NOT NULL,
  amount NUMERIC(19,6) NOT NULL,
  transaction_id VARCHAR(36) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  PRIMARY KEY (account_id, period_start),
  UNIQUE (transaction_id),
  FOREIGN KEY (account_id) REFERENCES accounts (account_id),
  FOREIGN KEY (transaction_id) REFERENCES ledger_transactions (transaction_id)
);
