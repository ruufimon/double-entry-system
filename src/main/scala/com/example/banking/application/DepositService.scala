package com.example.banking.application

import java.time.{Clock, Instant}
import java.util.UUID

import com.example.banking.domain.{
  Account,
  AccountId,
  DepositCompleted,
  DepositId,
  Money
}
import com.example.banking.ports.{AccountOperations, MessageBus}
import com.example.domain.DomainError

final case class DepositResult(account: Account, event: DepositCompleted)

final class DepositService(
    accountOperations: AccountOperations,
    messageBus: MessageBus,
    clock: Clock,
    generateDepositId: () => UUID
):
  def deposit(rawAccountId: String, amount: BigDecimal): Either[DomainError, DepositResult] =
    for
      accountId <- AccountId.from(rawAccountId)
      depositAmount <- Money.positiveDeposit(amount)
      depositId = DepositId(generateDepositId())
      occurredAt = Instant.now(clock)
      account <- accountOperations.deposit(
        depositId.value,
        accountId,
        depositAmount,
        occurredAt
      )
    yield
      val event = DepositCompleted(
        depositId = depositId,
        accountId = account.id,
        amount = depositAmount,
        resultingBalance = account.balance,
        occurredAt = occurredAt
      )
      messageBus.publish(event)
      DepositResult(account, event)
