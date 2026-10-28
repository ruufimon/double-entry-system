package com.example.banking.ledger

import java.time.{Instant, LocalDate, YearMonth, ZoneId}
import java.util.UUID

import com.example.banking.domain.{Account, AccountActivity, AccountId, TransferAccounts}
import com.example.domain.DomainError

trait LedgerRepository:
  def balance(accountId: AccountId): BigDecimal
  def snapshot(accountId: AccountId): AccountLedgerSnapshot
  def post(transaction: LedgerTransaction): Either[DomainError, Account]
  def postInterestCredit(
      transaction: LedgerTransaction,
      accountId: AccountId,
      period: YearMonth
  ): Either[DomainError, Boolean]
  def dailyClosingBalances(
      accountId: AccountId,
      from: LocalDate,
      through: LocalDate,
      zoneId: ZoneId
  ): Vector[(LocalDate, BigDecimal)]
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
