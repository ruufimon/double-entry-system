package com.example.banking.ledger

import java.time.Instant
import java.util.UUID

import com.example.banking.domain.{Account, AccountActivity, AccountId}
import com.example.domain.DomainError

trait LedgerRepository:
  def balance(accountId: AccountId): BigDecimal
  def post(transaction: LedgerTransaction): Either[DomainError, Account]
  def reverse(
      originalTransactionId: UUID,
      reversalTransactionId: UUID,
      occurredAt: Instant
  ): Either[DomainError, Account]
  def activities(accountId: AccountId): Vector[AccountActivity]
