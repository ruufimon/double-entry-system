package com.example.banking.ledger

import java.time.Instant
import java.util.UUID

import com.example.banking.domain.*
import com.example.banking.ports.AccountOperations
import com.example.domain.DomainError

final class LedgerBackedAccountOperations(
    ledgerRepository: LedgerRepository
) extends AccountOperations:
  override def find(accountId: AccountId): Either[DomainError, Account] =
    ledgerRepository.findAccount(accountId)

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
    ledgerRepository.activities(accountId)

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

    LedgerTransaction
      .create(
        transactionId,
        operation,
        Vector(
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
      .flatMap(ledgerRepository.post)
