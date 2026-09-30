package com.example.banking.domain

import java.time.Instant
import java.util.UUID

final case class AccountId private (value: String) derives CanEqual

object AccountId:
  private val ValidAccountId = "[A-Za-z0-9_-]{1,64}".r

  def from(rawAccountId: String): Either[BankingError, AccountId] =
    rawAccountId.trim match
      case ValidAccountId() => Right(AccountId(rawAccountId.trim))
      case _                => Left(BankingError.InvalidAccountId)

final case class Money private (amount: BigDecimal) derives CanEqual

object Money:
  def positiveDeposit(amount: BigDecimal): Either[BankingError, Money] =
    validate(amount, BankingError.InvalidDepositAmount.apply)

  def positiveWithdrawal(amount: BigDecimal): Either[BankingError, Money] =
    validate(amount, BankingError.InvalidWithdrawalAmount.apply)

  private def validate(
      amount: BigDecimal,
      invalidAmount: String => BankingError
  ): Either[BankingError, Money] =
    if amount <= 0 then
      Left(invalidAmount("Amount must be greater than zero"))
    else if amount.scale > 2 then
      Left(invalidAmount("Amount must have at most two decimal places"))
    else
      Right(Money(amount))

final case class Account(id: AccountId, balance: BigDecimal) derives CanEqual

sealed trait DomainEvent derives CanEqual:
  def occurredAt: Instant

final case class DepositId(value: UUID) derives CanEqual
final case class WithdrawalId(value: UUID) derives CanEqual

final case class DepositCompleted(
    depositId: DepositId,
    accountId: AccountId,
    amount: Money,
    resultingBalance: BigDecimal,
    occurredAt: Instant
) extends DomainEvent

final case class WithdrawalCompleted(
    withdrawalId: WithdrawalId,
    accountId: AccountId,
    amount: Money,
    resultingBalance: BigDecimal,
    occurredAt: Instant
) extends DomainEvent

enum BankingOperation derives CanEqual:
  case Deposit, Withdrawal

final case class AuditLogEntry(
    transactionId: UUID,
    operation: BankingOperation,
    accountId: AccountId,
    amount: Money,
    resultingBalance: BigDecimal,
    occurredAt: Instant
) derives CanEqual

object AuditLogEntry:
  def from(event: DepositCompleted): AuditLogEntry =
    AuditLogEntry(
      transactionId = event.depositId.value,
      operation = BankingOperation.Deposit,
      accountId = event.accountId,
      amount = event.amount,
      resultingBalance = event.resultingBalance,
      occurredAt = event.occurredAt
    )

  def from(event: WithdrawalCompleted): AuditLogEntry =
    AuditLogEntry(
      transactionId = event.withdrawalId.value,
      operation = BankingOperation.Withdrawal,
      accountId = event.accountId,
      amount = event.amount,
      resultingBalance = event.resultingBalance,
      occurredAt = event.occurredAt
    )

enum BankingError(val code: String, val message: String) derives CanEqual:
  case InvalidAccountId
      extends BankingError(
        "invalid_account_id",
        "Account ID must contain 1 to 64 letters, numbers, underscores, or hyphens"
      )
  case InvalidDepositAmount(details: String)
      extends BankingError("invalid_deposit_amount", details)
  case InvalidWithdrawalAmount(details: String)
      extends BankingError("invalid_withdrawal_amount", details)
  case AccountNotFound(accountId: String)
      extends BankingError("account_not_found", s"Account '$accountId' was not found")
  case InsufficientFunds(availableBalance: BigDecimal, requestedAmount: BigDecimal)
      extends BankingError(
        "insufficient_funds",
        s"Available balance is $availableBalance; requested withdrawal is $requestedAmount"
      )
  case InvalidRequest(details: String)
      extends BankingError("invalid_request", details)
