package com.example.banking.application

import java.time.{Clock, Instant}
import java.util.UUID

import com.example.banking.domain.{
  Account,
  AccountId,
  BankingError,
  DepositCompleted,
  DepositId,
  Money
}
import com.example.banking.ports.{AccountRepository, MessageBus}

final case class DepositResult(account: Account, event: DepositCompleted)

final class DepositService(
    accountRepository: AccountRepository,
    messageBus: MessageBus,
    clock: Clock,
    generateDepositId: () => UUID
):
  def deposit(rawAccountId: String, amount: BigDecimal): Either[BankingError, DepositResult] =
    for
      accountId <- AccountId.from(rawAccountId)
      depositAmount <- Money.positiveDeposit(amount)
      account <- accountRepository.deposit(accountId, depositAmount)
    yield
      val event = DepositCompleted(
        depositId = DepositId(generateDepositId()),
        accountId = account.id,
        amount = depositAmount,
        resultingBalance = account.balance,
        occurredAt = Instant.now(clock)
      )
      messageBus.publish(event)
      DepositResult(account, event)
