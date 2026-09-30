package com.example.banking.ledger

import java.time.Instant
import java.util.UUID

import com.example.banking.domain.{AccountId, BankingOperation, Currency, Money}
import com.example.domain.DomainError

enum LedgerDirection derives CanEqual:
  case Debit, Credit

enum LedgerAccount derives CanEqual:
  case Customer(accountId: AccountId)
  case Cash
  case BillerClearing

final case class LedgerEntry(
    account: LedgerAccount,
    direction: LedgerDirection,
    amount: Money,
    currency: Currency
) derives CanEqual:
  def reversed: LedgerEntry =
    copy(
      direction = direction match
        case LedgerDirection.Debit  => LedgerDirection.Credit
        case LedgerDirection.Credit => LedgerDirection.Debit
    )

final case class LedgerTransaction private (
    transactionId: UUID,
    operation: BankingOperation,
    entries: Vector[LedgerEntry],
    occurredAt: Instant,
    reversesTransactionId: Option[UUID]
) derives CanEqual

object LedgerTransaction:
  def create(
      transactionId: UUID,
      operation: BankingOperation,
      entries: Vector[LedgerEntry],
      occurredAt: Instant,
      reversesTransactionId: Option[UUID] = None
  ): Either[LedgerError, LedgerTransaction] =
    val currencies = entries.map(_.currency).distinct
    val customerEntryCount = entries.count {
      case LedgerEntry(LedgerAccount.Customer(_), _, _, _) => true
      case _                                                => false
    }
    val debitTotal = entries.collect {
      case LedgerEntry(_, LedgerDirection.Debit, amount, _) => amount.amount
    }.sum
    val creditTotal = entries.collect {
      case LedgerEntry(_, LedgerDirection.Credit, amount, _) => amount.amount
    }.sum

    if entries.size < 2 then
      Left(LedgerError.InvalidTransaction("A ledger transaction requires at least two entries"))
    else if customerEntryCount != 1 then
      Left(LedgerError.InvalidTransaction("A ledger transaction requires exactly one customer entry"))
    else if currencies != Vector(Currency.THB) then
      Left(LedgerError.InvalidTransaction("All ledger entries must use THB"))
    else if debitTotal != creditTotal then
      Left(LedgerError.UnbalancedTransaction(debitTotal, creditTotal))
    else if operation == BankingOperation.BillPaymentReversal && reversesTransactionId.isEmpty then
      Left(LedgerError.InvalidTransaction("A reversal must reference its original transaction"))
    else if operation != BankingOperation.BillPaymentReversal && reversesTransactionId.nonEmpty then
      Left(LedgerError.InvalidTransaction("Only a reversal may reference an original transaction"))
    else
      Right(
        LedgerTransaction(
          transactionId,
          operation,
          entries,
          occurredAt,
          reversesTransactionId
        )
      )

  def reversal(
      reversalTransactionId: UUID,
      original: LedgerTransaction,
      occurredAt: Instant
  ): Either[LedgerError, LedgerTransaction] =
    create(
      reversalTransactionId,
      BankingOperation.BillPaymentReversal,
      original.entries.map(_.reversed),
      occurredAt,
      Some(original.transactionId)
    )

enum LedgerError(val code: String, val message: String)
    extends DomainError
    derives CanEqual:
  case InvalidTransaction(details: String)
      extends LedgerError("invalid_ledger_transaction", details)
  case UnbalancedTransaction(debitTotal: BigDecimal, creditTotal: BigDecimal)
      extends LedgerError(
        "unbalanced_ledger_transaction",
        s"Ledger debits ($debitTotal) must equal credits ($creditTotal)"
      )
  case DuplicateTransaction(transactionId: UUID)
      extends LedgerError(
        "duplicate_ledger_transaction",
        s"Ledger transaction '$transactionId' already exists"
      )
  case TransactionNotFound(transactionId: UUID)
      extends LedgerError(
        "ledger_transaction_not_found",
        s"Ledger transaction '$transactionId' was not found"
      )
  case TransactionAlreadyReversed(transactionId: UUID)
      extends LedgerError(
        "ledger_transaction_already_reversed",
        s"Ledger transaction '$transactionId' has already been reversed"
      )
