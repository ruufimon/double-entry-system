package com.example.billpayment.application

import java.nio.charset.StandardCharsets
import java.time.{Clock, Instant}
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

import com.example.banking.domain.*
import com.example.banking.ports.MessageBus
import com.example.billpayment.domain.*
import com.example.billpayment.ports.{
  BillerGateway,
  BillPaymentInquiryRepository,
  BillPaymentProcessRepository
}

final class BillPaymentProcessManager(
    billerGateway: BillerGateway,
    inquiryRepository: BillPaymentInquiryRepository,
    processRepository: BillPaymentProcessRepository,
    messageBus: MessageBus,
    clock: Clock
):
  private val subscribed = new AtomicBoolean(false)

  def subscribe(): Unit =
    if subscribed.compareAndSet(false, true) then
      messageBus.subscribe {
        case event: BillPaymentChargeCompleted => handleChargeCompleted(event)
        case event: BillPaymentChargeRejected  => handleChargeRejected(event)
        case event: BillPaymentChargeReversed  => handleChargeReversed(event)
        case event: BillPaymentChargeReversalRejected =>
          handleChargeReversalRejected(event)
        case _                               => ()
      }

  private def handleChargeCompleted(event: BillPaymentChargeCompleted): Unit =
    transitionProcess(event.paymentId, BillPaymentStatus.AwaitingAccountCharge) { process =>
      if process.accountId != event.accountId then process
      else
        process.copy(
          status = BillPaymentStatus.Settling,
          resultingBalance = Some(event.resultingBalance),
          updatedAt = Instant.now(clock)
        )
    }.filter(_.status == BillPaymentStatus.Settling).foreach(settleWithBiller)

  private def settleWithBiller(process: BillPaymentProcess): Unit =
    val paidAt = Instant.now(clock)
    billerGateway.settle(process.debt, process.paymentId, paidAt) match
      case Right(receipt) =>
        processRepository.update(process.paymentId) { current =>
          if current.status == BillPaymentStatus.Settling then
            current.copy(
              status = BillPaymentStatus.Completed,
              billerReceipt = Some(receipt),
              updatedAt = receipt.paidAt
            )
          else current
        }
        inquiryRepository.complete(process.inquiryId)
        messageBus.publish(
          BillPaymentCompleted(
            paymentId = process.paymentId,
            inquiryId = process.inquiryId,
            accountId = process.accountId,
            billerCode = process.debt.billerCode,
            amount = process.debt.amount,
            resultingBalance = process.resultingBalance.get,
            billerReceiptCode = receipt.receiptCode,
            occurredAt = receipt.paidAt
          )
        )
      case Left(error) =>
        val currentTime = Instant.now(clock)
        processRepository.update(process.paymentId) { current =>
          if current.status == BillPaymentStatus.Settling then
            current.copy(
              status = BillPaymentStatus.Reversing,
              failure = Some(BillPaymentFailure(error.code, error.message)),
              updatedAt = currentTime
            )
          else current
        }
        messageBus.publish(
          BillPaymentChargeReversalRequested(
            paymentId = process.paymentId.value,
            reversalId = reversalIdFor(process.paymentId),
            accountId = process.accountId,
            occurredAt = currentTime
          )
        )

  private def handleChargeRejected(event: BillPaymentChargeRejected): Unit =
    transitionProcess(event.paymentId, BillPaymentStatus.AwaitingAccountCharge) { process =>
      if process.accountId != event.accountId then process
      else
        inquiryRepository.release(process.inquiryId)
        process.copy(
          status = BillPaymentStatus.Failed,
          failure = Some(BillPaymentFailure(event.errorCode, event.errorMessage)),
          updatedAt = event.occurredAt
        )
    }

  private def handleChargeReversed(event: BillPaymentChargeReversed): Unit =
    transitionProcess(event.paymentId, BillPaymentStatus.Reversing) { process =>
      if process.accountId != event.accountId then process
      else
        inquiryRepository.release(process.inquiryId)
        process.copy(
          status = BillPaymentStatus.Failed,
          resultingBalance = Some(event.resultingBalance),
          updatedAt = event.occurredAt
        )
    }

  private def handleChargeReversalRejected(
      event: BillPaymentChargeReversalRejected
  ): Unit =
    transitionProcess(event.paymentId, BillPaymentStatus.Reversing) { process =>
      if process.accountId != event.accountId then process
      else
        val compensationError = BillPaymentError.CompensationFailed(event.errorMessage)
        process.copy(
          status = BillPaymentStatus.ManualReview,
          failure = Some(
            BillPaymentFailure(compensationError.code, compensationError.message)
          ),
          updatedAt = event.occurredAt
        )
    }

  private def transitionProcess(
      rawPaymentId: UUID,
      expectedStatus: BillPaymentStatus
  )(transition: BillPaymentProcess => BillPaymentProcess): Option[BillPaymentProcess] =
    var transitioned = false
    val result = processRepository.update(BillPaymentId(rawPaymentId)) { process =>
      if process.status == expectedStatus then
        val updated = transition(process)
        transitioned = updated != process
        updated
      else process
    }
    if transitioned then result else None

  private def reversalIdFor(paymentId: BillPaymentId): UUID =
    UUID.nameUUIDFromBytes(
      s"bill-payment-reversal:${paymentId.value}".getBytes(StandardCharsets.UTF_8)
    )
