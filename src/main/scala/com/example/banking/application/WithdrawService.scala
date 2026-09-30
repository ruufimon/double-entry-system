package com.example.banking.application

import java.time.{Clock, Instant}
import java.util.UUID

import com.example.banking.domain.{
  Account,
  AccountId,
  BankingError,
  Money,
  WithdrawalCompleted,
  WithdrawalId
}
import com.example.banking.ports.{AccountRepository, MessageBus}

final case class WithdrawalResult(account: Account, event: WithdrawalCompleted)

final class WithdrawService(
    accountRepository: AccountRepository,
    messageBus: MessageBus,
    clock: Clock,
    generateWithdrawalId: () => UUID
):
  def withdraw(rawAccountId: String, amount: BigDecimal): Either[BankingError, WithdrawalResult] =
    for
      accountId <- AccountId.from(rawAccountId)
      withdrawalAmount <- Money.positiveWithdrawal(amount)
      account <- accountRepository.withdraw(accountId, withdrawalAmount)
    yield
      val event = WithdrawalCompleted(
        withdrawalId = WithdrawalId(generateWithdrawalId()),
        accountId = account.id,
        amount = withdrawalAmount,
        resultingBalance = account.balance,
        occurredAt = Instant.now(clock)
      )
      messageBus.publish(event)
      WithdrawalResult(account, event)
