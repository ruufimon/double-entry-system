package com.example.banking.ports

import java.time.Instant

import com.example.banking.domain.{
  BankingError,
  BillerCode,
  BillerDebt,
  BillerReceipt,
  BillerReference,
  BillPaymentId
}

trait BillerGateway:
  def fetchDebt(
      billerCode: BillerCode,
      referenceCode1: BillerReference,
      referenceCode2: BillerReference
  ): Either[BankingError, BillerDebt]

  def settle(
      debt: BillerDebt,
      paymentId: BillPaymentId,
      paidAt: Instant
  ): Either[BankingError, BillerReceipt]
