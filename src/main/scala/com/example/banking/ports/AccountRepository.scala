package com.example.banking.ports

import com.example.banking.domain.{Account, AccountId, AccountOpening}
import com.example.domain.DomainError

trait AccountRepository:
  def open(accountId: AccountId): AccountOpening
  def find(accountId: AccountId): Either[DomainError, Account]
