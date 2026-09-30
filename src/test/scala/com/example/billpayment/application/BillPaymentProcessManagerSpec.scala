package com.example.billpayment.application

import java.time.{Clock, Duration, Instant, ZoneOffset}
import java.util.UUID

import com.example.banking.domain.{
  AccountId,
  BillPaymentChargeReversalRejected,
  BillPaymentChargeReversed,
  Money
}
import com.example.banking.infrastructure.LocalMessageBus
import com.example.billpayment.domain.*
import com.example.billpayment.infrastructure.{
  BillSeed,
  InMemoryBillerGateway,
  InMemoryBillPaymentInquiryRepository,
  InMemoryBillPaymentProcessRepository
}
import com.example.domain.DomainError
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class BillPaymentProcessManagerSpec extends AnyFunSuite with Matchers:
  private val Now = Instant.parse("2026-09-30T05:00:00Z")

  test("reversal rejection moves the payment to manual review and keeps inquiry locked") {
    val fixture = createFixture(BillPaymentStatus.Reversing)
    val reversalId = UUID.randomUUID()

    fixture.messageBus.publish(
      BillPaymentChargeReversalRejected(
        fixture.process.paymentId.value,
        reversalId,
        fixture.process.accountId,
        "reversal_failed",
        "Ledger reversal failed",
        Now
      )
    )
    fixture.messageBus.awaitIdle(Duration.ofSeconds(3)) shouldBe true

    val updated = fixture.processRepository.find(fixture.process.paymentId).get
    updated.status shouldBe BillPaymentStatus.ManualReview
    updated.failure.map(_.code) shouldBe Some("bill_payment_compensation_failed")
    fixture.inquiryRepository.claim(
      fixture.process.inquiryId,
      fixture.process.accountId,
      Now
    ) shouldBe Left(BillPaymentError.InProgress)
  }

  test("out-of-order reversal result does not change an awaiting debit payment") {
    val fixture = createFixture(BillPaymentStatus.AwaitingAccountCharge)

    fixture.messageBus.publish(
      BillPaymentChargeReversed(
        fixture.process.paymentId.value,
        UUID.randomUUID(),
        fixture.process.accountId,
        BigDecimal("100.00"),
        Now
      )
    )
    fixture.messageBus.awaitIdle(Duration.ofSeconds(3)) shouldBe true

    fixture.processRepository.find(fixture.process.paymentId).map(_.status) shouldBe Some(
      BillPaymentStatus.AwaitingAccountCharge
    )
  }

  private final case class Fixture(
      messageBus: LocalMessageBus,
      inquiryRepository: InMemoryBillPaymentInquiryRepository,
      processRepository: InMemoryBillPaymentProcessRepository,
      process: BillPaymentProcess
  )

  private def createFixture(status: BillPaymentStatus): Fixture =
    val messageBus = new LocalMessageBus()
    val inquiryRepository = new InMemoryBillPaymentInquiryRepository()
    val processRepository = new InMemoryBillPaymentProcessRepository()
    val accountId = requireRight(AccountId.from("account-123"))
    val billerCode = requireRight(BillerCode.from("demo-biller"))
    val reference1 = requireRight(BillerReference.from("referenceCode1", "customer-001"))
    val reference2 = requireRight(BillerReference.from("referenceCode2", "invoice-001"))
    val amount = requireRight(Money.positive(BigDecimal("100.00")))
    val inquiry = BillPaymentInquiry(
      BillPaymentInquiryId(UUID.randomUUID()),
      accountId,
      BillerDebt(billerCode, reference1, reference2, amount),
      Now.plusSeconds(300),
      BillPaymentInquiryStatus.Processing
    )
    val process = BillPaymentProcess(
      BillPaymentId(UUID.randomUUID()),
      inquiry.inquiryId,
      accountId,
      inquiry.debt,
      status,
      Some(BigDecimal("50.00")),
      None,
      Some(BillPaymentFailure("biller_settlement_failed", "Biller unavailable")),
      Now,
      Now
    )
    inquiryRepository.save(inquiry)
    processRepository.save(process)
    val gateway = new InMemoryBillerGateway(
      Vector(BillSeed("demo-biller", "customer-001", "invoice-001", BigDecimal("100.00")))
    )
    new BillPaymentProcessManager(
      gateway,
      inquiryRepository,
      processRepository,
      messageBus,
      Clock.fixed(Now, ZoneOffset.UTC)
    ).subscribe()
    Fixture(messageBus, inquiryRepository, processRepository, process)

  private def requireRight[A](result: Either[?, A]): A =
    result match
      case Right(value) => value
      case Left(error: DomainError) => fail(s"Expected Right, got ${error.code}: ${error.message}")
      case Left(error) => fail(s"Expected Right, got $error")
