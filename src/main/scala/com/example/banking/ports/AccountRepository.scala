package com.example.banking.ports

import com.example.banking.domain.{Account, AccountId, BankingError, Money}

trait AccountRepository:
  def deposit(accountId: AccountId, amount: Money): Either[BankingError, Account]
  def withdraw(accountId: AccountId, amount: Money): Either[BankingError, Account]
