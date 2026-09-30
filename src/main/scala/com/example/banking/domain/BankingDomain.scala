package com.example.banking.domain

import java.time.Instant
import java.util.UUID

import com.example.domain.DomainError
import com.example.messaging.InternalMessage

final case class AccountId private (value: String) derives CanEqual

object AccountId:
  private val ValidAccountId = "[A-Za-z0-9_-]{1,64}".r

  def from(rawAccountId: String): Either[BankingError, AccountId] =
    val normalizedAccountId = Option(rawAccountId).fold("")(_.trim)
    normalizedAccountId match
      case ValidAccountId() => Right(AccountId(normalizedAccountId))
      case _                => Left(BankingError.InvalidAccountId)

final case class Money private (amount: BigDecimal) derives CanEqual

object Money:
  def positiveDeposit(amount: BigDecimal): Either[BankingError, Money] =
    positive(amount).left.map(BankingError.InvalidDepositAmount.apply)

  def positiveWithdrawal(amount: BigDecimal): Either[BankingError, Money] =
    positive(amount).left.map(BankingError.InvalidWithdrawalAmount.apply)

  def positive(amount: BigDecimal): Either[String, Money] =
    if amount <= 0 then
      Left("Amount must be greater than zero")
    else if amount.scale > 2 then
      Left("Amount must have at most two decimal places")
    else
      Right(Money(amount))

final case class Account(id: AccountId, balance: BigDecimal) derives CanEqual

enum Currency(val code: String) derives CanEqual:
  case THB extends Currency("THB")

enum BalanceEffect derives CanEqual:
  case Increase, Decrease

enum AccountActivityStatus derives CanEqual:
  case Posted, Reversed

trait DomainEvent extends InternalMessage derives CanEqual

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
  case Deposit, Withdrawal, BillPayment, BillPaymentReversal

final case class AccountActivity(
    transactionId: UUID,
    operation: BankingOperation,
    effect: BalanceEffect,
    amount: BigDecimal,
    currency: Currency,
    balanceAfter: BigDecimal,
    occurredAt: Instant,
    status: AccountActivityStatus,
    originalTransactionId: Option[UUID]
) derives CanEqual

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

enum BankingError(val code: String, val message: String) extends DomainError derives CanEqual:
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
        s"Available balance is $availableBalance; requested amount is $requestedAmount"
      )
  case InvalidRequest(details: String)
      extends BankingError("invalid_request", details)
