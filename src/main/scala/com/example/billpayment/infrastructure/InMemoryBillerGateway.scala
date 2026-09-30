package com.example.billpayment.infrastructure

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

import com.example.billpayment.domain.{
  BillerCode,
  BillerDebt,
  BillerReceipt,
  BillerReference,
  BillPaymentAmount,
  BillPaymentError,
  BillPaymentId
}
import com.example.billpayment.ports.BillerGateway

final case class BillSeed(
    billerCode: String,
    referenceCode1: String,
    referenceCode2: String,
    debt: BigDecimal
)

final class InMemoryBillerGateway(initialBills: Vector[BillSeed]) extends BillerGateway:
  private final case class BillKey(
      billerCode: String,
      referenceCode1: String,
      referenceCode2: String
  )

  private final case class BillState(debt: BigDecimal, paid: Boolean)

  private val bills = new ConcurrentHashMap[BillKey, BillState]()
  private val receipts = new ConcurrentHashMap[BillPaymentId, BillerReceipt]()
  private val supportedBillers = initialBills.map(_.billerCode).toSet

  initialBills.foreach { seed =>
    bills.put(
      BillKey(seed.billerCode, seed.referenceCode1, seed.referenceCode2),
      BillState(seed.debt, paid = false)
    )
  }

  override def fetchDebt(
      billerCode: BillerCode,
      referenceCode1: BillerReference,
      referenceCode2: BillerReference
  ): Either[BillPaymentError, BillerDebt] =
    if !supportedBillers.contains(billerCode.value) then
      Left(BillPaymentError.BillerNotFound(billerCode.value))
    else
      val billKey = BillKey(billerCode.value, referenceCode1.value, referenceCode2.value)
      Option(bills.get(billKey)) match
        case None => Left(BillPaymentError.BillNotFound)
        case Some(billState) if billState.paid => Left(BillPaymentError.BillNotPayable)
        case Some(billState) =>
          BillPaymentAmount.from(billState.debt).map { amount =>
            BillerDebt(billerCode, referenceCode1, referenceCode2, amount)
          }

  override def settle(
      debt: BillerDebt,
      paymentId: BillPaymentId,
      paidAt: Instant
  ): Either[BillPaymentError, BillerReceipt] =
    bills.synchronized {
      Option(receipts.get(paymentId)) match
        case Some(receipt) => Right(receipt)
        case None =>
          val billKey = BillKey(
            debt.billerCode.value,
            debt.referenceCode1.value,
            debt.referenceCode2.value
          )
          Option(bills.get(billKey)) match
            case None => Left(BillPaymentError.BillNotFound)
            case Some(billState) if billState.paid => Left(BillPaymentError.BillNotPayable)
            case Some(billState) if billState.debt != debt.amount.amount =>
              Left(BillPaymentError.SettlementFailed("Biller debt changed before settlement"))
            case Some(billState) =>
              val receipt = BillerReceipt(
                s"${debt.billerCode.value}-${paymentId.value}",
                paidAt
              )
              bills.put(billKey, billState.copy(paid = true))
              receipts.put(paymentId, receipt)
              Right(receipt)
    }
