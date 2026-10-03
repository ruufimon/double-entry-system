package com.example.banking.infrastructure

import com.example.banking.domain.{Account, AccountId, AccountOpening, BankingError}
import com.example.banking.ports.AccountRepository
import com.example.domain.DomainError

final class InMemoryAccountRepository extends AccountRepository:
  private var accountIds = Set.empty[AccountId]

  override def open(accountId: AccountId): AccountOpening = synchronized {
    val created = !accountIds.contains(accountId)
    accountIds = accountIds + accountId
    AccountOpening(Account(accountId, BigDecimal(0)), created)
  }

  override def find(accountId: AccountId): Either[DomainError, Account] = synchronized {
    if accountIds.contains(accountId) then Right(Account(accountId, BigDecimal(0)))
    else Left(BankingError.AccountNotFound(accountId.value))
  }
