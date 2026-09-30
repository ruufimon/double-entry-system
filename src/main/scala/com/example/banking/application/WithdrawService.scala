package com.example.banking.application

import java.time.{Clock, Instant}
import java.util.UUID

import com.example.banking.domain.{
  Account,
  AccountId,
  Money,
  WithdrawalCompleted,
  WithdrawalId
}
import com.example.banking.ports.{AccountOperations, MessageBus}
import com.example.domain.DomainError

final case class WithdrawalResult(account: Account, event: WithdrawalCompleted)

final class WithdrawService(
    accountOperations: AccountOperations,
    messageBus: MessageBus,
    clock: Clock,
    generateWithdrawalId: () => UUID
):
  def withdraw(rawAccountId: String, amount: BigDecimal): Either[DomainError, WithdrawalResult] =
    for
      accountId <- AccountId.from(rawAccountId)
      withdrawalAmount <- Money.positiveWithdrawal(amount)
      withdrawalId = WithdrawalId(generateWithdrawalId())
      occurredAt = Instant.now(clock)
      account <- accountOperations.withdraw(
        withdrawalId.value,
        accountId,
        withdrawalAmount,
        occurredAt
      )
    yield
      val event = WithdrawalCompleted(
        withdrawalId = withdrawalId,
        accountId = account.id,
        amount = withdrawalAmount,
        resultingBalance = account.balance,
        occurredAt = occurredAt
      )
      messageBus.publish(event)
      WithdrawalResult(account, event)
