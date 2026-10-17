package com.example.biller

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.*

/** How the fake biller behaves when a bill is inquired or settled. */
enum BillerScenario(val wireName: String) derives CanEqual:
  case Ok extends BillerScenario("ok")
  case SettleDeclined extends BillerScenario("settle_declined")
  case SettleError extends BillerScenario("settle_error")
  case SettleTimeout extends BillerScenario("settle_timeout")
  case InquiryError extends BillerScenario("inquiry_error")

object BillerScenario:
  def fromWireName(name: String): Option[BillerScenario] =
    values.find(_.wireName == name)

final case class FakeBillKey(
    billerCode: String,
    referenceCode1: String,
    referenceCode2: String
) derives CanEqual

final case class FakeBill(
    key: FakeBillKey,
    amount: BigDecimal,
    paid: Boolean,
    scenario: BillerScenario,
    latencyMs: Option[Long]
)

final case class FakeReceipt(receiptCode: String, paidAt: Instant)

enum FakeBillerError(val code: String, val message: String) derives CanEqual:
  case BillerNotFound(billerCode: String)
      extends FakeBillerError("biller_not_found", s"Biller '$billerCode' was not found")
  case BillNotFound
      extends FakeBillerError("bill_not_found", "No bill was found for the supplied references")
  case BillNotPayable
      extends FakeBillerError("bill_not_payable", "The bill has already been paid")
  case AmountMismatch(expected: BigDecimal)
      extends FakeBillerError("amount_mismatch", s"Payment amount must equal the debt of $expected")

final class FakeBillerStore(seed: Vector[FakeBill], initialLatencyMs: Long):
  private val bills = new ConcurrentHashMap[FakeBillKey, FakeBill]()
  private val receipts = new ConcurrentHashMap[String, FakeReceipt]()
  @volatile private var globalLatencyMs = initialLatencyMs

  reset()

  def latencyMs: Long = globalLatencyMs

  def setLatencyMs(latencyMs: Long): Unit = globalLatencyMs = latencyMs

  def all: Vector[FakeBill] =
    bills.values.asScala.toVector.sortBy { bill =>
      (bill.key.billerCode, bill.key.referenceCode1, bill.key.referenceCode2)
    }

  def find(key: FakeBillKey): Either[FakeBillerError, FakeBill] =
    if !bills.keySet.asScala.exists(_.billerCode == key.billerCode) then
      Left(FakeBillerError.BillerNotFound(key.billerCode))
    else Option(bills.get(key)).toRight(FakeBillerError.BillNotFound)

  def upsert(bill: FakeBill): Unit = bills.put(bill.key, bill)

  def remove(key: FakeBillKey): Boolean = bills.remove(key) != null

  def reset(): Unit =
    bills.synchronized {
      bills.clear()
      receipts.clear()
      seed.foreach(upsert)
    }

  /** Marks the bill paid. Repeating a payment ID returns the original receipt. */
  def settle(
      key: FakeBillKey,
      paymentId: String,
      amount: BigDecimal,
      paidAt: Instant
  ): Either[FakeBillerError, FakeReceipt] =
    bills.synchronized {
      Option(receipts.get(paymentId)) match
        case Some(receipt) => Right(receipt)
        case None =>
          find(key).flatMap { bill =>
            if bill.paid then Left(FakeBillerError.BillNotPayable)
            else if bill.amount != amount then Left(FakeBillerError.AmountMismatch(bill.amount))
            else
              val receipt = FakeReceipt(s"${key.billerCode}-$paymentId", paidAt)
              bills.put(key, bill.copy(paid = true))
              receipts.put(paymentId, receipt)
              Right(receipt)
          }
    }

object FakeBillerStore:
  private def demoBill(
      referenceCode2: String,
      amount: String,
      scenario: BillerScenario = BillerScenario.Ok,
      latencyMs: Option[Long] = None
  ): FakeBill =
    FakeBill(
      FakeBillKey("demo-biller", "customer-001", referenceCode2),
      BigDecimal(amount),
      paid = false,
      scenario,
      latencyMs
    )

  val DefaultSeed: Vector[FakeBill] = Vector(
    demoBill("invoice-001", "100.00"),
    demoBill("invoice-002", "25.00"),
    demoBill("invoice-decline", "40.00", BillerScenario.SettleDeclined),
    demoBill("invoice-error", "40.00", BillerScenario.SettleError),
    demoBill("invoice-timeout", "40.00", BillerScenario.SettleTimeout),
    demoBill("invoice-slow", "60.00", latencyMs = Some(3000L))
  )
