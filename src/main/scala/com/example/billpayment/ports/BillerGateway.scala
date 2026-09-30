package com.example.billpayment.ports

import java.time.Instant

import com.example.billpayment.domain.{
  BillerCode,
  BillerDebt,
  BillerReceipt,
  BillerReference,
  BillPaymentError,
  BillPaymentId
}

trait BillerGateway:
  def fetchDebt(
      billerCode: BillerCode,
      referenceCode1: BillerReference,
      referenceCode2: BillerReference
  ): Either[BillPaymentError, BillerDebt]

  def settle(
      debt: BillerDebt,
      paymentId: BillPaymentId,
      paidAt: Instant
  ): Either[BillPaymentError, BillerReceipt]
