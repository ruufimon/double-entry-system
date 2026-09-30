package com.example.banking.ports

import java.time.Instant
import java.util.UUID

import com.example.banking.domain.{Account, AccountActivity, AccountId, Money}
import com.example.domain.DomainError

trait AccountOperations:
  def find(accountId: AccountId): Either[DomainError, Account]

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
