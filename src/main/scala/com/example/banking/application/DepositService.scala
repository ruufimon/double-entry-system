package com.example.banking.application

import com.example.banking.domain.{Account, AccountId, BankingError, Money}
import com.example.banking.ports.AccountRepository

final class DepositService(accountRepository: AccountRepository):
  def deposit(rawAccountId: String, amount: BigDecimal): Either[BankingError, Account] =
    for
      accountId <- AccountId.from(rawAccountId)
      depositAmount <- Money.positive(amount)
      account <- accountRepository.deposit(accountId, depositAmount)
    yield account
