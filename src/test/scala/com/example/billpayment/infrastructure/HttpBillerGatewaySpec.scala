package com.example.billpayment.infrastructure

import java.time.{Duration, Instant}
import java.util.UUID

import com.example.biller.FakeBillerServer
import com.example.billpayment.domain.{
  BillerCode,
  BillerDebt,
  BillerReference,
  BillPaymentError,
  BillPaymentId
}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class HttpBillerGatewaySpec extends AnyFunSuite with Matchers with BeforeAndAfterAll:
  private val billerServer =
    FakeBillerServer.create(0, settleTimeoutDelay = Duration.ofSeconds(2))
  private lazy val gateway = new HttpBillerGateway(
    s"http://127.0.0.1:${billerServer.port}",
    Duration.ofMillis(500)
  )
  private val paidAt = Instant.parse("2026-10-17T00:00:00Z")

  override protected def beforeAll(): Unit =
    super.beforeAll()
    billerServer.start()

  override protected def afterAll(): Unit =
    try billerServer.close()
    finally super.afterAll()

  test("fetches a debt and settles it") {
    val debt = fetch("invoice-002").fold(error => fail(error.message), identity)
    debt.amount.amount shouldBe BigDecimal("25.00")

    val paymentId = BillPaymentId(UUID.randomUUID())
    val receipt = gateway.settle(debt, paymentId, paidAt)
    receipt.map(_.paidAt) shouldBe Right(paidAt)
    gateway.settle(debt, paymentId, paidAt) shouldBe receipt
    fetch("invoice-002") shouldBe Left(BillPaymentError.BillNotPayable)
  }

  test("maps missing billers and bills") {
    gateway.fetchDebt(
      BillerCode.unsafe("unknown-biller"),
      reference("customer-001"),
      reference("invoice-001")
    ) shouldBe Left(BillPaymentError.BillerNotFound("unknown-biller"))
    fetch("missing") shouldBe Left(BillPaymentError.BillNotFound)
  }

  test("maps biller settlement failures to SettlementFailed") {
    Seq("invoice-decline", "invoice-error", "invoice-timeout").foreach { referenceCode2 =>
      val debt = fetch(referenceCode2).fold(error => fail(error.message), identity)
      gateway.settle(debt, BillPaymentId(UUID.randomUUID()), paidAt) match
        case Left(BillPaymentError.SettlementFailed(_)) => succeed
        case other => fail(s"$referenceCode2 settled unexpectedly: $other")
    }
  }

  test("reports an unreachable biller as unavailable") {
    val offline = new HttpBillerGateway("http://127.0.0.1:1", Duration.ofMillis(500))
    offline.fetchDebt(
      BillerCode.unsafe("demo-biller"),
      reference("customer-001"),
      reference("invoice-001")
    ) match
      case Left(BillPaymentError.BillerUnavailable(_)) => succeed
      case other => fail(s"Expected BillerUnavailable, got $other")
  }

  private def fetch(referenceCode2: String): Either[BillPaymentError, BillerDebt] =
    gateway.fetchDebt(
      BillerCode.unsafe("demo-biller"),
      reference("customer-001"),
      reference(referenceCode2)
    )

  private def reference(value: String): BillerReference =
    BillerReference.unsafe(value)
