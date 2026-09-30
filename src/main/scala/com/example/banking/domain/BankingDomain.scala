package com.example.banking.domain

import java.time.Instant
import java.util.UUID

import scala.util.Try

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
    validate(amount, BankingError.InvalidDepositAmount.apply)

  def positiveWithdrawal(amount: BigDecimal): Either[BankingError, Money] =
    validate(amount, BankingError.InvalidWithdrawalAmount.apply)

  def positiveBillPayment(amount: BigDecimal): Either[BankingError, Money] =
    validate(amount, BankingError.InvalidBillPaymentAmount.apply)

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
final case class BillPaymentId(value: UUID) derives CanEqual

final case class BillPaymentInquiryId(value: UUID) derives CanEqual

object BillPaymentInquiryId:
  def from(rawInquiryId: String): Either[BankingError, BillPaymentInquiryId] =
    Try(UUID.fromString(rawInquiryId)).toEither
      .map(BillPaymentInquiryId.apply)
      .left
      .map(_ => BankingError.InvalidBillPaymentInquiryId)

final case class BillerCode private (value: String) derives CanEqual

object BillerCode:
  private val ValidBillerCode = "[A-Za-z0-9_-]{1,64}".r

  def from(rawBillerCode: String): Either[BankingError, BillerCode] =
    val normalizedBillerCode = Option(rawBillerCode).fold("")(_.trim)
    normalizedBillerCode match
      case ValidBillerCode() => Right(BillerCode(normalizedBillerCode))
      case _                 => Left(BankingError.InvalidBillerCode)

final case class BillerReference private (value: String) derives CanEqual

object BillerReference:
  def from(fieldName: String, rawReference: String): Either[BankingError, BillerReference] =
    val normalizedReference = Option(rawReference).fold("")(_.trim)
    if normalizedReference.nonEmpty && normalizedReference.length <= 128 then
      Right(BillerReference(normalizedReference))
    else
      Left(BankingError.InvalidBillerReference(fieldName))

final case class BillerDebt(
    billerCode: BillerCode,
    referenceCode1: BillerReference,
    referenceCode2: BillerReference,
    amount: Money
) derives CanEqual

final case class BillerReceipt(receiptCode: String, paidAt: Instant) derives CanEqual

enum BillPaymentInquiryStatus derives CanEqual:
  case Pending, Processing, Completed

final case class BillPaymentInquiry(
    inquiryId: BillPaymentInquiryId,
    accountId: AccountId,
    debt: BillerDebt,
    expiresAt: Instant,
    status: BillPaymentInquiryStatus
) derives CanEqual

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

final case class BillPaymentCompleted(
    paymentId: BillPaymentId,
    inquiryId: BillPaymentInquiryId,
    accountId: AccountId,
    billerCode: BillerCode,
    amount: Money,
    resultingBalance: BigDecimal,
    billerReceiptCode: String,
    occurredAt: Instant
) extends DomainEvent

enum BankingOperation derives CanEqual:
  case Deposit, Withdrawal, BillPayment

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

  def from(event: BillPaymentCompleted): AuditLogEntry =
    AuditLogEntry(
      transactionId = event.paymentId.value,
      operation = BankingOperation.BillPayment,
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
  case InvalidBillPaymentAmount(details: String)
      extends BankingError("invalid_bill_payment_amount", details)
  case InvalidBillerCode
      extends BankingError(
        "invalid_biller_code",
        "Biller code must contain 1 to 64 letters, numbers, underscores, or hyphens"
      )
  case InvalidBillerReference(fieldName: String)
      extends BankingError(
        "invalid_biller_reference",
        s"$fieldName must contain 1 to 128 characters"
      )
  case InvalidBillPaymentInquiryId
      extends BankingError("invalid_bill_payment_inquiry_id", "Inquiry ID must be a valid UUID")
  case AccountNotFound(accountId: String)
      extends BankingError("account_not_found", s"Account '$accountId' was not found")
  case InsufficientFunds(availableBalance: BigDecimal, requestedAmount: BigDecimal)
      extends BankingError(
        "insufficient_funds",
        s"Available balance is $availableBalance; requested amount is $requestedAmount"
      )
  case BillerNotFound(billerCode: String)
      extends BankingError("biller_not_found", s"Biller '$billerCode' was not found")
  case BillNotFound
      extends BankingError("bill_not_found", "No bill was found for the supplied references")
  case BillNotPayable
      extends BankingError("bill_not_payable", "The bill has already been paid")
  case BillPaymentInquiryNotFound
      extends BankingError("bill_payment_inquiry_not_found", "Bill payment inquiry was not found")
  case BillPaymentInquiryExpired
      extends BankingError("bill_payment_inquiry_expired", "Bill payment inquiry has expired")
  case BillPaymentAlreadyCompleted
      extends BankingError("bill_payment_already_completed", "Bill payment is already completed")
  case BillPaymentInProgress
      extends BankingError("bill_payment_in_progress", "Bill payment confirmation is in progress")
  case BillerSettlementFailed(details: String)
      extends BankingError("biller_settlement_failed", details)
  case InvalidRequest(details: String)
      extends BankingError("invalid_request", details)
