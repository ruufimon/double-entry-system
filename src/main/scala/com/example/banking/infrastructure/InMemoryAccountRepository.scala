package com.example.banking.infrastructure

import java.util.concurrent.ConcurrentHashMap

import com.example.banking.domain.{Account, AccountId, BankingError, Money}
import com.example.banking.ports.AccountRepository

final class InMemoryAccountRepository extends AccountRepository:
  private val balances = new ConcurrentHashMap[AccountId, BigDecimal]()

  override def deposit(accountId: AccountId, amount: Money): Either[BankingError, Account] =
    val updatedBalance = balances.compute(
      accountId,
      (_, currentBalance) => Option(currentBalance).getOrElse(BigDecimal(0)) + amount.amount
    )

    Right(Account(accountId, updatedBalance))
