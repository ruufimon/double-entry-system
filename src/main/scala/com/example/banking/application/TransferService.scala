package com.example.banking.application

import java.time.{Clock, Instant}
import java.util.UUID

import com.example.banking.domain.*
import com.example.banking.ports.{AccountOperations, MessageBus}
import com.example.domain.DomainError

final case class TransferResult(
    transferId: TransferId,
    sourceAccount: Account,
    destinationAccount: Account,
    amount: Money,
    occurredAt: Instant,
    event: TransferCompleted
)

final class TransferService(
    accountOperations: AccountOperations,
    messageBus: MessageBus,
    clock: Clock,
    generateTransferId: () => UUID
):
  def transfer(
      rawSourceAccountId: String,
      rawDestinationAccountId: String,
      amount: BigDecimal
  ): Either[DomainError, TransferResult] =
    for
      sourceAccountId <- AccountId.from(rawSourceAccountId)
      destinationAccountId <- AccountId.from(rawDestinationAccountId)
      _ <- Either.cond(
        sourceAccountId != destinationAccountId,
        (),
        BankingError.SameAccountTransfer
      )
      transferAmount <- Money.positiveTransfer(amount)
      transferId = TransferId(generateTransferId())
      occurredAt = Instant.now(clock)
      accounts <- accountOperations.transfer(
        transferId.value,
        sourceAccountId,
        destinationAccountId,
        transferAmount,
        occurredAt
      )
    yield
      val event = TransferCompleted(
        transferId,
        sourceAccountId,
        destinationAccountId,
        transferAmount,
        accounts.source.balance,
        accounts.destination.balance,
        occurredAt
      )
      messageBus.publish(event)
      TransferResult(
        transferId,
        accounts.source,
        accounts.destination,
        transferAmount,
        occurredAt,
        event
      )
