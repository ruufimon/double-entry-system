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
