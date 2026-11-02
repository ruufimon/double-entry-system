package com.example.banking.ledger

import java.time.Instant
import java.util.UUID

import cats.data.NonEmptyVector
import com.example.banking.domain.*
import com.example.banking.ports.{AccountOperations, AccountRepository}
import com.example.domain.DomainError

final class LedgerBackedAccountOperations(
    ledgerRepository: LedgerRepository,
    accountRepository: AccountRepository
) extends AccountOperations:
  override def open(accountId: AccountId): Either[DomainError, AccountOpening] =
    Right(accountRepository.open(accountId))

  override def find(accountId: AccountId): Either[DomainError, Account] =
    accountRepository
      .find(accountId)
      .map(account => account.copy(balance = ledgerRepository.balance(accountId)))

  override def overview(accountId: AccountId): Either[DomainError, AccountOverview] =
    accountRepository.find(accountId).map(toOverview)

  override def allOverviews: Vector[AccountOverview] =
    accountRepository.all.map(toOverview)

  override def deposit(
      transactionId: UUID,
      accountId: AccountId,
      amount: Money,
      occurredAt: Instant
  ): Either[DomainError, Account] =
    post(
      transactionId,
      BankingOperation.Deposit,
      accountId,
      amount,
      customerDirection = LedgerDirection.Credit,
      counterAccount = LedgerAccount.Cash,
      occurredAt
    )

  override def withdraw(
      transactionId: UUID,
      accountId: AccountId,
      amount: Money,
      occurredAt: Instant
  ): Either[DomainError, Account] =
    post(
      transactionId,
      BankingOperation.Withdrawal,
      accountId,
      amount,
      customerDirection = LedgerDirection.Debit,
      counterAccount = LedgerAccount.Cash,
      occurredAt
    )

  override def transfer(
      transactionId: UUID,
      sourceAccountId: AccountId,
      destinationAccountId: AccountId,
      amount: Money,
      occurredAt: Instant
  ): Either[DomainError, TransferAccounts] =
    for
      _ <- accountRepository.find(sourceAccountId)
      _ <- accountRepository.find(destinationAccountId)
      transaction <- LedgerTransaction.create(
        transactionId,
        BankingOperation.Transfer,
        NonEmptyVector.of(
          LedgerEntry(
            LedgerAccount.Customer(sourceAccountId),
            LedgerDirection.Debit,
            amount,
            Currency.THB
          ),
          LedgerEntry(
            LedgerAccount.Customer(destinationAccountId),
            LedgerDirection.Credit,
            amount,
            Currency.THB
          )
        ),
        occurredAt
      )
      accounts <- ledgerRepository.transfer(transaction, sourceAccountId, destinationAccountId)
    yield accounts

  override def chargeForBillPayment(
      transactionId: UUID,
      accountId: AccountId,
      amount: Money,
      occurredAt: Instant
  ): Either[DomainError, Account] =
    post(
      transactionId,
      BankingOperation.BillPayment,
      accountId,
      amount,
      customerDirection = LedgerDirection.Debit,
      counterAccount = LedgerAccount.BillerClearing,
      occurredAt
    )

  override def reverseBillPaymentCharge(
      originalTransactionId: UUID,
      reversalTransactionId: UUID,
      occurredAt: Instant
  ): Either[DomainError, Account] =
    ledgerRepository.reverse(originalTransactionId, reversalTransactionId, occurredAt)

  override def activities(
      accountId: AccountId
  ): Either[DomainError, Vector[AccountActivity]] =
    accountRepository.find(accountId).map(_ => ledgerRepository.activities(accountId))

  override def adjust(
      adjustmentId: AdjustmentId,
      accountId: AccountId,
      direction: AdjustmentDirection,
      amount: Money,
      memo: AdjustmentMemo,
      adjustsTransactionId: Option[UUID],
      occurredAt: Instant
  ): Either[DomainError, BalanceAdjustment] =
    val (customerDirection, counterDirection) = direction match
      case AdjustmentDirection.Credit => (LedgerDirection.Credit, LedgerDirection.Debit)
      case AdjustmentDirection.Debit  => (LedgerDirection.Debit, LedgerDirection.Credit)

    for
      _ <- accountRepository.find(accountId)
      transaction <- LedgerTransaction.create(
        adjustmentId.value,
        BankingOperation.Adjustment,
        NonEmptyVector.of(
          LedgerEntry(LedgerAccount.Customer(accountId), customerDirection, amount, Currency.THB),
          LedgerEntry(LedgerAccount.Adjustments, counterDirection, amount, Currency.THB)
        ),
        occurredAt,
        adjustsTransactionId = adjustsTransactionId
      )
      account <- ledgerRepository.adjust(transaction, memo)
    yield BalanceAdjustment(
      adjustmentId,
      accountId,
      direction,
      amount,
      memo,
      adjustsTransactionId,
      account.balance,
      occurredAt
    )

  override def adjustments(
      accountId: AccountId
  ): Either[DomainError, Vector[BalanceAdjustment]] =
    accountRepository.find(accountId).map { _ =>
      val memos = ledgerRepository.adjustmentMemos(accountId)
      ledgerRepository.activities(accountId).collect {
        case activity if activity.operation == BankingOperation.Adjustment =>
          BalanceAdjustment(
            adjustmentId = AdjustmentId(activity.transactionId),
            accountId = accountId,
            direction = activity.effect match
              case BalanceEffect.Increase => AdjustmentDirection.Credit
              case BalanceEffect.Decrease => AdjustmentDirection.Debit,
            amount = Money.of(activity.amount),
            memo = memos(activity.transactionId),
            adjustsTransactionId = activity.originalTransactionId,
            balanceAfter = activity.balanceAfter,
            occurredAt = activity.occurredAt
          )
      }
    }

  private def post(
      transactionId: UUID,
      operation: BankingOperation,
      accountId: AccountId,
      amount: Money,
      customerDirection: LedgerDirection,
      counterAccount: LedgerAccount,
      occurredAt: Instant
  ): Either[DomainError, Account] =
    val counterDirection = customerDirection match
      case LedgerDirection.Debit  => LedgerDirection.Credit
      case LedgerDirection.Credit => LedgerDirection.Debit

    for
      _ <- accountRepository.find(accountId)
      transaction <- LedgerTransaction.create(
        transactionId,
        operation,
        NonEmptyVector.of(
          LedgerEntry(
            LedgerAccount.Customer(accountId),
            customerDirection,
            amount,
            Currency.THB
          ),
          LedgerEntry(counterAccount, counterDirection, amount, Currency.THB)
        ),
        occurredAt
      )
      account <- ledgerRepository.post(transaction)
    yield account

  private def toOverview(account: Account): AccountOverview =
    val snapshot = ledgerRepository.snapshot(account.id)
    AccountOverview(
      account = account.copy(balance = snapshot.balance),
      activities = snapshot.activities
    )
