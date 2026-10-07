package com.example.banking.http

import com.example.banking.domain.{
  AccountActivity,
  AccountActivityStatus,
  BalanceEffect,
  BankingOperation
}

private[http] object AccountResponseMapping:
  def toActivityResponse(activity: AccountActivity): AccountActivityResponse =
    AccountActivityResponse(
      transactionId = activity.transactionId.toString,
      operation = activity.operation match
        case BankingOperation.Deposit             => "deposit"
        case BankingOperation.Withdrawal          => "withdrawal"
        case BankingOperation.BillPayment         => "bill_payment"
        case BankingOperation.BillPaymentReversal => "bill_payment_reversal",
      effect = activity.effect match
        case BalanceEffect.Increase => "increase"
        case BalanceEffect.Decrease => "decrease",
      amount = activity.amount,
      currency = activity.currency.code,
      balanceAfter = activity.balanceAfter,
      occurredAt = activity.occurredAt.toString,
      status = activity.status match
        case AccountActivityStatus.Posted   => "posted"
        case AccountActivityStatus.Reversed => "reversed",
      originalTransactionId = activity.originalTransactionId.map(_.toString)
    )
