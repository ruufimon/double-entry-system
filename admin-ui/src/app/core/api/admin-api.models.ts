export type AccountStatus = 'active';
export type BankingOperation =
  | 'deposit'
  | 'withdrawal'
  | 'transfer_out'
  | 'transfer_in'
  | 'bill_payment'
  | 'bill_payment_reversal';

export interface AdminAccountSummary {
  accountId: string;
  currency: 'THB';
  status: AccountStatus;
  balance: number;
  activityCount: number;
  lastActivityAt: string | null;
}

export interface AccountActivity {
  transactionId: string;
  operation: BankingOperation;
  effect: 'increase' | 'decrease';
  amount: number;
  currency: 'THB';
  balanceAfter: number;
  occurredAt: string;
  status: 'posted' | 'reversed';
  originalTransactionId: string | null;
  counterpartyAccountId: string | null;
}

export interface AdminAccountDetail extends AdminAccountSummary {
  activities: readonly AccountActivity[];
}

export interface ErrorResponse {
  error: string;
  message: string;
}
