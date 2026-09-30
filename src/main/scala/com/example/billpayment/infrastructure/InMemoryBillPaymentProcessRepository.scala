package com.example.billpayment.infrastructure

import java.util.concurrent.ConcurrentHashMap

import com.example.billpayment.domain.{BillPaymentId, BillPaymentProcess}
import com.example.billpayment.ports.BillPaymentProcessRepository

final class InMemoryBillPaymentProcessRepository extends BillPaymentProcessRepository:
  private val processes = new ConcurrentHashMap[BillPaymentId, BillPaymentProcess]()

  override def save(process: BillPaymentProcess): Unit =
    processes.put(process.paymentId, process)

  override def find(paymentId: BillPaymentId): Option[BillPaymentProcess] =
    Option(processes.get(paymentId))

  override def update(
      paymentId: BillPaymentId
  )(transition: BillPaymentProcess => BillPaymentProcess): Option[BillPaymentProcess] =
    processes.synchronized {
      Option(processes.get(paymentId)).map { currentProcess =>
        val updatedProcess = transition(currentProcess)
        processes.put(paymentId, updatedProcess)
        updatedProcess
      }
    }
