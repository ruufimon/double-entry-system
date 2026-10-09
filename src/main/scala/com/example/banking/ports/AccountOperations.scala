package com.example.banking.ports

import java.time.Instant
import java.util.UUID

import com.example.banking.domain.{
  Account,
  AccountActivity,
  AccountId,
  AccountOpening,
  AccountOverview,
  Money,
  TransferAccounts
}
import com.example.domain.DomainError

trait AccountOperations:
  def open(accountId: AccountId): Either[DomainError, AccountOpening]

  def find(accountId: AccountId): Either[DomainError, Account]

  def overview(accountId: AccountId): Either[DomainError, AccountOverview]

  def allOverviews: Vector[AccountOverview]

  def deposit(
      transactionId: UUID,
      accountId: AccountId,
      amount: Money,
      occurredAt: Instant
  ): Either[DomainError, Account]

  def withdraw(
      transactionId: UUID,
      accountId: AccountId,
      amount: Money,
      occurredAt: Instant
  ): Either[DomainError, Account]

  def transfer(
      transactionId: UUID,
      sourceAccountId: AccountId,
      destinationAccountId: AccountId,
      amount: Money,
      occurredAt: Instant
  ): Either[DomainError, TransferAccounts]

  def chargeForBillPayment(
      transactionId: UUID,
      accountId: AccountId,
      amount: Money,
      occurredAt: Instant
  ): Either[DomainError, Account]

  def reverseBillPaymentCharge(
      originalTransactionId: UUID,
      reversalTransactionId: UUID,
      occurredAt: Instant
  ): Either[DomainError, Account]

  def activities(accountId: AccountId): Either[DomainError, Vector[AccountActivity]]
