package com.example.banking.ports

import com.example.banking.domain.{Account, AccountId, BankingError, Money}

trait AccountRepository:
  def find(accountId: AccountId): Either[BankingError, Account]
  def deposit(accountId: AccountId, amount: Money): Either[BankingError, Account]
  def withdraw(accountId: AccountId, amount: Money): Either[BankingError, Account]
  def refund(accountId: AccountId, amount: Money): Unit
