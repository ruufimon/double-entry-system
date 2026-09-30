package com.example.banking.infrastructure

import java.util.concurrent.ConcurrentHashMap

import com.example.banking.domain.{Account, AccountId, BankingError, Money}
import com.example.banking.ports.AccountRepository

final class InMemoryAccountRepository extends AccountRepository:
  private val balances = new ConcurrentHashMap[AccountId, BigDecimal]()

  override def deposit(accountId: AccountId, amount: Money): Either[BankingError, Account] =
    balances.synchronized {
      val currentBalance = Option(balances.get(accountId)).getOrElse(BigDecimal(0))
      val updatedBalance = currentBalance + amount.amount
      balances.put(accountId, updatedBalance)
      Right(Account(accountId, updatedBalance))
    }

  override def withdraw(accountId: AccountId, amount: Money): Either[BankingError, Account] =
    balances.synchronized {
      Option(balances.get(accountId)) match
        case None => Left(BankingError.AccountNotFound(accountId.value))
        case Some(currentBalance) if currentBalance < amount.amount =>
          Left(BankingError.InsufficientFunds(currentBalance, amount.amount))
        case Some(currentBalance) =>
          val updatedBalance = currentBalance - amount.amount
          balances.put(accountId, updatedBalance)
          Right(Account(accountId, updatedBalance))
    }
