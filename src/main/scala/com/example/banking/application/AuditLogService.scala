package com.example.banking.application

import java.util.concurrent.atomic.AtomicBoolean

import com.example.banking.domain.{
  AuditLogEntry,
  BankingOperation,
  DepositCompleted,
  WithdrawalCompleted
}
import com.example.billpayment.domain.BillPaymentCompleted
import com.example.banking.ports.{AuditLogRepository, MessageBus}

final class AuditLogService(
    messageBus: MessageBus,
    auditLogRepository: AuditLogRepository
):
  private val subscribed = new AtomicBoolean(false)

  def subscribe(): Unit =
    if subscribed.compareAndSet(false, true) then
      messageBus.subscribe {
        case event: DepositCompleted => auditLogRepository.append(AuditLogEntry.from(event))
        case event: WithdrawalCompleted => auditLogRepository.append(AuditLogEntry.from(event))
        case event: BillPaymentCompleted =>
          auditLogRepository.append(
            AuditLogEntry(
              transactionId = event.paymentId.value,
              operation = BankingOperation.BillPayment,
              accountId = event.accountId,
              amount = event.amount,
              resultingBalance = event.resultingBalance,
              occurredAt = event.occurredAt
            )
          )
        case _ => ()
      }
