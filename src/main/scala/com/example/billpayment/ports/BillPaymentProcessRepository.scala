package com.example.billpayment.ports

import com.example.billpayment.domain.{BillPaymentId, BillPaymentProcess}

trait BillPaymentProcessRepository:
  def save(process: BillPaymentProcess): Unit
  def find(paymentId: BillPaymentId): Option[BillPaymentProcess]
  def update(
      paymentId: BillPaymentId
  )(transition: BillPaymentProcess => BillPaymentProcess): Option[BillPaymentProcess]
