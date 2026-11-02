package com.example.banking.application

import java.time.{Clock, Instant}
import java.util.UUID

import com.example.banking.domain.{
  AccountId,
  AdjustmentDirection,
  AdjustmentId,
  AdjustmentMemo,
  AdjustmentReason,
  AdminActor,
  BalanceAdjusted,
  BalanceAdjustment,
  BankingError,
  Money
}
import com.example.banking.ports.{AccountOperations, MessageBus}
import com.example.domain.DomainError

/** Raw admin input for a balance adjustment, validated by [[AdjustmentService]]. */
final case class AdjustmentCommand(
    accountId: String,
    direction: Option[String],
    amount: Option[BigDecimal],
    reason: Option[String],
    actor: Option[String],
    idempotencyKey: Option[String],
    adjustsTransactionId: Option[UUID]
)

final class AdjustmentService(
    accountOperations: AccountOperations,
    messageBus: MessageBus,
    clock: Clock
):
  def adjust(command: AdjustmentCommand): Either[DomainError, BalanceAdjustment] =
    for
      actor <- AdminActor.from(command.actor)
      adjustmentId <- AdjustmentId.fromIdempotencyKey(command.idempotencyKey)
      accountId <- AccountId.from(command.accountId)
      direction <- AdjustmentDirection.from(command.direction)
      amount <- command.amount
        .toRight(BankingError.InvalidAdjustmentAmount("Amount is required"))
        .flatMap(Money.positiveAdjustment)
      reason <- AdjustmentReason.from(command.reason)
      adjustment <- accountOperations.adjust(
        adjustmentId,
        accountId,
        direction,
        amount,
        AdjustmentMemo(reason, actor),
        command.adjustsTransactionId,
        Instant.now(clock)
      )
    yield
      messageBus.publish(BalanceAdjusted(adjustment))
      adjustment

  def adjustments(rawAccountId: String): Either[DomainError, Vector[BalanceAdjustment]] =
    AccountId.from(rawAccountId).flatMap(accountOperations.adjustments)
