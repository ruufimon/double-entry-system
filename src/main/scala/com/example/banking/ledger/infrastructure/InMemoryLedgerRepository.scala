package com.example.banking.ledger.infrastructure

import java.time.Instant
import java.util.UUID

import cats.data.State
import cats.syntax.all.*
import com.example.banking.domain.*
import com.example.banking.ledger.*
import com.example.domain.DomainError

final class InMemoryLedgerRepository extends LedgerRepository:
  private var journal = Vector.empty[LedgerTransaction]

  override def balance(accountId: AccountId): BigDecimal = synchronized {
    balanceOf(accountId)
  }

  override def snapshot(accountId: AccountId): AccountLedgerSnapshot = synchronized {
    val accountActivities = activitiesOf(accountId)
    AccountLedgerSnapshot(
      balance = accountActivities.lastOption.fold(BigDecimal(0))(_.balanceAfter),
      activities = accountActivities
    )
  }

  override def post(transaction: LedgerTransaction): Either[DomainError, Account] =
    synchronized {
      append(transaction)
    }

  override def reverse(
      originalTransactionId: UUID,
      reversalTransactionId: UUID,
      occurredAt: Instant
  ): Either[DomainError, Account] =
    synchronized {
      for
        original <- journal
          .find(_.transactionId == originalTransactionId)
          .toRight(LedgerError.TransactionNotFound(originalTransactionId))
        _ <- Either.cond(
          !journal.exists(_.reversesTransactionId.contains(originalTransactionId)),
          (),
          LedgerError.TransactionAlreadyReversed(originalTransactionId)
        )
        reversal <- LedgerTransaction.reversal(reversalTransactionId, original, occurredAt)
        account <- append(reversal)
      yield account
    }

  override def activities(
      accountId: AccountId
  ): Vector[AccountActivity] =
    synchronized {
      activitiesOf(accountId)
    }

  def transactions: Vector[LedgerTransaction] = synchronized(journal)

  private def append(transaction: LedgerTransaction): Either[DomainError, Account] =
    if journal.exists(_.transactionId == transaction.transactionId) then
      Left(LedgerError.DuplicateTransaction(transaction.transactionId))
    else
      val (accountId, customerLedgerEntry) = transaction.entries.collectFirst {
        case entry @ LedgerEntry(LedgerAccount.Customer(accountId), _, _, _) =>
          accountId -> entry
      }.get
      val currentBalance = balanceOf(accountId)

      customerLedgerEntry.direction match
        case LedgerDirection.Debit if currentBalance < customerLedgerEntry.amount.amount =>
          Left(
            BankingError.InsufficientFunds(
              currentBalance,
              customerLedgerEntry.amount.amount
            )
          )
        case _ =>
          journal = journal :+ transaction
          Right(Account(accountId, applyEntry(currentBalance, customerLedgerEntry)))

  private def balanceOf(accountId: AccountId): BigDecimal =
    journal.foldLeft(BigDecimal(0)) { (balance, transaction) =>
      customerEntry(transaction, accountId).fold(balance)(applyEntry(balance, _))
    }

  private def activitiesOf(accountId: AccountId): Vector[AccountActivity] =
    val reversedTransactionIds = journal.flatMap(_.reversesTransactionId).toSet
    journal
      .traverse(activityFor(accountId, reversedTransactionIds))
      .runA(BigDecimal(0))
      .value
      .flatten

  private def activityFor(
      accountId: AccountId,
      reversedTransactionIds: Set[UUID]
  )(transaction: LedgerTransaction): State[BigDecimal, Option[AccountActivity]] =
    State { runningBalance =>
      customerEntry(transaction, accountId) match
        case Some(entry) =>
          val balanceAfter = applyEntry(runningBalance, entry)
          val activity = AccountActivity(
            transactionId = transaction.transactionId,
            operation = transaction.operation,
            effect = entry.direction match
              case LedgerDirection.Credit => BalanceEffect.Increase
              case LedgerDirection.Debit  => BalanceEffect.Decrease,
            amount = entry.amount.amount,
            currency = entry.currency,
            balanceAfter = balanceAfter,
            occurredAt = transaction.occurredAt,
            status =
              if reversedTransactionIds.contains(transaction.transactionId) then
                AccountActivityStatus.Reversed
              else AccountActivityStatus.Posted,
            originalTransactionId = transaction.reversesTransactionId
          )
          balanceAfter -> Some(activity)
        case None => runningBalance -> None
    }

  private def customerEntry(
      transaction: LedgerTransaction,
      accountId: AccountId
  ): Option[LedgerEntry] =
    transaction.entries.find(_.account == LedgerAccount.Customer(accountId))

  private def applyEntry(balance: BigDecimal, entry: LedgerEntry): BigDecimal =
    entry.direction match
      case LedgerDirection.Credit => balance + entry.amount.amount
      case LedgerDirection.Debit  => balance - entry.amount.amount
