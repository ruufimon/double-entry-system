export interface AccountBalance {
  accountId: string;
  currency: 'THB';
  balance: number;
}

export interface AccountResponse extends AccountBalance {
  status: 'active';
}

export type BankingOperation =
  | 'deposit'
  | 'withdrawal'
  | 'bill_payment'
  | 'bill_payment_reversal';

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
}

export interface AccountOverview extends AccountBalance {
  activities: readonly AccountActivity[];
}

export interface BalanceChangeResponse {
  accountId: string;
  balance: number;
}

export interface BillPaymentInquiryRequest {
  billerCode: string;
  referenceCode1: string;
  referenceCode2: string;
}

export interface BillPaymentInquiry {
  inquiryId: string;
  billerCode: string;
  referenceCode1: string;
  referenceCode2: string;
  currentDebt: number;
  expiresAt: string;
}

export type BillPaymentStatus =
  | 'awaiting_account_charge'
  | 'settling'
  | 'reversing'
  | 'completed'
  | 'failed'
  | 'manual_review';

export interface BillPaymentAccepted {
  paymentId: string;
  inquiryId: string;
  accountId: string;
  status: BillPaymentStatus;
}

export interface BillPaymentFailure {
  error: string;
  message: string;
}

export interface BillPaymentProcess {
  paymentId: string;
  inquiryId: string;
  accountId: string;
  billerCode: string;
  amount: number;
  currency: 'THB';
  status: BillPaymentStatus;
  resultingBalance: number | null;
  billerReceiptCode: string | null;
  paidAt: string | null;
  failure: BillPaymentFailure | null;
}

export interface ErrorResponse {
  error: string;
  message: string;
}
