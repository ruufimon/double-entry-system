package com.example.banking.application

import java.time.{Clock, Instant}
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

import com.example.banking.domain.*
import com.example.banking.ports.{AccountOperations, MessageBus}
import com.example.messaging.InternalMessage

final class AccountMessageHandler(
    accountOperations: AccountOperations,
    messageBus: MessageBus,
    clock: Clock
):
  private val subscribed = new AtomicBoolean(false)
  private val outcomes = new ConcurrentHashMap[UUID, InternalMessage]()

  def subscribe(): Unit =
    if subscribed.compareAndSet(false, true) then
      messageBus.subscribe {
        case request: BillPaymentChargeRequested => handleBillPaymentCharge(request)
        case request: BillPaymentChargeReversalRequested =>
          handleBillPaymentChargeReversal(request)
        case _ => ()
      }

  private def handleBillPaymentCharge(request: BillPaymentChargeRequested): Unit =
    publishOnce(request.paymentId) {
      accountOperations.chargeForBillPayment(
        request.paymentId,
        request.accountId,
        request.amount,
        request.occurredAt
      ) match
        case Right(account) =>
          BillPaymentChargeCompleted(
            request.paymentId,
            request.accountId,
            request.amount,
            account.balance,
            Instant.now(clock)
          )
        case Left(error) =>
          BillPaymentChargeRejected(
            request.paymentId,
            request.accountId,
            error.code,
            error.message,
            Instant.now(clock)
          )
    }

  private def handleBillPaymentChargeReversal(
      request: BillPaymentChargeReversalRequested
  ): Unit =
    publishOnce(request.reversalId) {
      accountOperations.reverseBillPaymentCharge(
        request.paymentId,
        request.reversalId,
        request.occurredAt
      ) match
        case Right(account) =>
          BillPaymentChargeReversed(
            request.paymentId,
            request.reversalId,
            request.accountId,
            account.balance,
            Instant.now(clock)
          )
        case Left(error) =>
          BillPaymentChargeReversalRejected(
            request.paymentId,
            request.reversalId,
            request.accountId,
            error.code,
            error.message,
            Instant.now(clock)
          )
    }

  private def publishOnce(messageId: UUID)(createOutcome: => InternalMessage): Unit =
    val outcome = outcomes.synchronized {
      Option(outcomes.get(messageId)).getOrElse {
        val createdOutcome = createOutcome
        outcomes.put(messageId, createdOutcome)
        createdOutcome
      }
    }
    messageBus.publish(outcome)
