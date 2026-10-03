package com.example.banking.application

import com.example.banking.domain.{AccountId, AccountOpening}
import com.example.banking.ports.AccountOperations
import com.example.domain.DomainError

final class AccountService(accountOperations: AccountOperations):
  def open(rawAccountId: String): Either[DomainError, AccountOpening] =
    AccountId.from(rawAccountId).flatMap(accountOperations.open)
