package com.example.banking.ledger

import java.time.Instant
import java.util.UUID

import com.example.banking.domain.{
  Account,
  AccountActivity,
  AccountId,
  AdjustmentMemo,
  TransferAccounts
}
import com.example.domain.DomainError

trait LedgerRepository:
  def balance(accountId: AccountId): BigDecimal
  def snapshot(accountId: AccountId): AccountLedgerSnapshot
  def post(transaction: LedgerTransaction): Either[DomainError, Account]
  def transfer(
      transaction: LedgerTransaction,
      sourceAccountId: AccountId,
      destinationAccountId: AccountId
  ): Either[DomainError, TransferAccounts]
  def reverse(
      originalTransactionId: UUID,
      reversalTransactionId: UUID,
      occurredAt: Instant
  ): Either[DomainError, Account]
  def activities(accountId: AccountId): Vector[AccountActivity]

  /** Posts an adjustment and its memo atomically. A linked transaction must touch the account. */
  def adjust(transaction: LedgerTransaction, memo: AdjustmentMemo): Either[DomainError, Account]

  /** Memos of the account's adjustments, keyed by transaction ID. */
  def adjustmentMemos(accountId: AccountId): Map[UUID, AdjustmentMemo]
