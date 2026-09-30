package com.example.billpayment.application

import java.time.{Clock, Duration, Instant, ZoneId, ZoneOffset}
import java.util.UUID
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}

import com.example.banking.application.{AccountMessageHandler, DepositService}
import com.example.banking.domain.{AccountActivityStatus, AccountId}
import com.example.banking.infrastructure.LocalMessageBus
import com.example.banking.ledger.LedgerBackedAccountOperations
import com.example.banking.ledger.infrastructure.InMemoryLedgerRepository
import com.example.billpayment.domain.*
import com.example.billpayment.infrastructure.{
  BillSeed,
  InMemoryBillerGateway,
  InMemoryBillPaymentInquiryRepository,
  InMemoryBillPaymentProcessRepository
}
import com.example.billpayment.ports.BillerGateway
import com.example.domain.DomainError
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class BillPaymentServiceSpec extends AnyFunSuite with Matchers:
  private val Account = "account-123"
  private val Biller = "demo-biller"
  private val Reference1 = "customer-001"
  private val Reference2 = "invoice-001"
  private val InquiryId = UUID.fromString("ac7d454f-f960-423c-a4f8-94f76e71ad24")
  private val PaymentId = UUID.fromString("54834b9b-a26e-4b52-917c-0e62270e49da")
  private val StartTime = Instant.parse("2026-09-30T02:00:00Z")

  test("inquiry returns debt without synchronously calling the account ledger") {
    val fixture = createFixture()

    val result = requireRight(fixture.service.inquire(Account, Biller, Reference1, Reference2))

    result.inquiry.inquiryId.value shouldBe InquiryId
    result.inquiry.debt.amount.amount shouldBe BigDecimal("100.00")
    result.inquiry.expiresAt shouldBe StartTime.plusSeconds(300)
  }

  test("confirmation returns acceptance before background settlement completes") {
    val delegateGateway = createBillerGateway()
    val settlementStarted = new CountDownLatch(1)
    val allowSettlement = new CountDownLatch(1)
    val blockingGateway = new BillerGateway:
      override def fetchDebt(
          billerCode: BillerCode,
          referenceCode1: BillerReference,
          referenceCode2: BillerReference
      ): Either[BillPaymentError, BillerDebt] =
        delegateGateway.fetchDebt(billerCode, referenceCode1, referenceCode2)

      override def settle(
          debt: BillerDebt,
          paymentId: BillPaymentId,
          paidAt: Instant
      ): Either[BillPaymentError, BillerReceipt] =
        settlementStarted.countDown()
        allowSettlement.await(3, TimeUnit.SECONDS)
        delegateGateway.settle(debt, paymentId, paidAt)

    val fixture = createFixture(blockingGateway)
    fund(fixture, BigDecimal("150.00"))
    val inquiry = inquire(fixture)

    val accepted = requireRight(
      fixture.service.confirm(Account, inquiry.inquiryId.value.toString)
    )

    accepted.process.status shouldBe BillPaymentStatus.AwaitingAccountCharge
    accepted.process.paymentId.value shouldBe PaymentId
    settlementStarted.await(3, TimeUnit.SECONDS) shouldBe true
    allowSettlement.countDown()
    awaitWorkflow(fixture)
    payment(fixture, accepted.process.paymentId).status shouldBe BillPaymentStatus.Completed
  }

  test("successful account debit settles the bill and publishes completion") {
    val fixture = createFixture()
    val completedEvent = new AtomicReference[Option[BillPaymentCompleted]](None)
    fixture.messageBus.subscribe {
      case event: BillPaymentCompleted => completedEvent.set(Some(event))
      case _                           => ()
    }
    fund(fixture, BigDecimal("150.00"))
    val accepted = confirm(fixture, inquire(fixture))

    awaitWorkflow(fixture)

    val completed = payment(fixture, accepted.process.paymentId)
    completed.status shouldBe BillPaymentStatus.Completed
    completed.resultingBalance shouldBe Some(BigDecimal("50.00"))
    completed.billerReceipt.map(_.receiptCode).get should include(PaymentId.toString)
    completedEvent.get().map(_.paymentId) shouldBe Some(accepted.process.paymentId)
  }

  test("insufficient funds fails asynchronously and leaves the inquiry retryable") {
    val fixture = createFixture()
    fund(fixture, BigDecimal("50.00"))
    val inquiry = inquire(fixture)
    val firstAttempt = confirm(fixture, inquiry)
    awaitWorkflow(fixture)

    val failed = payment(fixture, firstAttempt.process.paymentId)
    failed.status shouldBe BillPaymentStatus.Failed
    failed.failure.map(_.code) shouldBe Some("insufficient_funds")

    fund(fixture, BigDecimal("50.00"))
    val retry = confirm(fixture, inquiry)
    awaitWorkflow(fixture)
    payment(fixture, retry.process.paymentId).status shouldBe BillPaymentStatus.Completed
  }

  test("an unknown account fails during asynchronous debit processing") {
    val fixture = createFixture()
    val inquiry = inquire(fixture)
    val accepted = confirm(fixture, inquiry)

    awaitWorkflow(fixture)

    val failed = payment(fixture, accepted.process.paymentId)
    failed.status shouldBe BillPaymentStatus.Failed
    failed.failure.map(_.code) shouldBe Some("account_not_found")
  }

  test("expired inquiry cannot be confirmed") {
    val fixture = createFixture()
    val inquiry = inquire(fixture)
    fixture.clock.set(StartTime.plusSeconds(300))

    fixture.service.confirm(Account, inquiry.inquiryId.value.toString) shouldBe Left(
      BillPaymentError.InquiryExpired
    )
  }

  test("completed inquiry cannot be confirmed twice and paid bill is no longer payable") {
    val fixture = createFixture()
    fund(fixture, BigDecimal("250.00"))
    val inquiry = inquire(fixture)
    confirm(fixture, inquiry)
    awaitWorkflow(fixture)

    fixture.service.confirm(Account, inquiry.inquiryId.value.toString) shouldBe Left(
      BillPaymentError.AlreadyCompleted
    )
    fixture.service.inquire(Account, Biller, Reference1, Reference2) shouldBe Left(
      BillPaymentError.BillNotPayable
    )
  }

  test("settlement failure appends a reversal and leaves the inquiry retryable") {
    val delegateGateway = createBillerGateway()
    val failSettlement = new AtomicBoolean(true)
    val failingGateway = new BillerGateway:
      override def fetchDebt(
          billerCode: BillerCode,
          referenceCode1: BillerReference,
          referenceCode2: BillerReference
      ): Either[BillPaymentError, BillerDebt] =
        delegateGateway.fetchDebt(billerCode, referenceCode1, referenceCode2)

      override def settle(
          debt: BillerDebt,
          paymentId: BillPaymentId,
          paidAt: Instant
      ): Either[BillPaymentError, BillerReceipt] =
        if failSettlement.getAndSet(false) then
          Left(BillPaymentError.SettlementFailed("Biller is temporarily unavailable"))
        else delegateGateway.settle(debt, paymentId, paidAt)

    val fixture = createFixture(failingGateway)
    fund(fixture, BigDecimal("150.00"))
    val inquiry = inquire(fixture)
    val failedAttempt = confirm(fixture, inquiry)
    awaitWorkflow(fixture)

    val failedPayment = payment(fixture, failedAttempt.process.paymentId)
    failedPayment.status shouldBe BillPaymentStatus.Failed
    failedPayment.resultingBalance shouldBe Some(BigDecimal("150.00"))
    accountBalance(fixture) shouldBe BigDecimal("150.00")
    val accountId = requireRight(AccountId.from(Account))
    val failedActivities = requireRight(fixture.accountOperations.activities(accountId))
    failedActivities.takeRight(2).map(_.operation.toString) shouldBe Vector(
      "BillPayment",
      "BillPaymentReversal"
    )
    failedActivities(failedActivities.size - 2).status shouldBe AccountActivityStatus.Reversed

    val retry = confirm(fixture, inquiry)
    awaitWorkflow(fixture)
    payment(fixture, retry.process.paymentId).status shouldBe BillPaymentStatus.Completed
    accountBalance(fixture) shouldBe BigDecimal("50.00")
  }

  test("inquiry remains bound to its originating account") {
    val fixture = createFixture()
    val inquiry = inquire(fixture)

    fixture.service.confirm("other-account", inquiry.inquiryId.value.toString) shouldBe Left(
      BillPaymentError.InquiryNotFound
    )
  }

  private final case class Fixture(
      accountOperations: LedgerBackedAccountOperations,
      messageBus: LocalMessageBus,
      clock: MutableClock,
      depositService: DepositService,
      service: BillPaymentService
  )

  private def createFixture(
      billerGateway: BillerGateway = createBillerGateway()
  ): Fixture =
    val ledgerRepository = new InMemoryLedgerRepository()
    val accountOperations = new LedgerBackedAccountOperations(ledgerRepository)
    val messageBus = new LocalMessageBus()
    val clock = new MutableClock(StartTime)
    val inquiryRepository = new InMemoryBillPaymentInquiryRepository()
    val processRepository = new InMemoryBillPaymentProcessRepository()
    val depositService = new DepositService(
      accountOperations,
      messageBus,
      clock,
      () => UUID.randomUUID()
    )
    val paymentSequence = new AtomicInteger(0)
    val service = new BillPaymentService(
      billerGateway,
      inquiryRepository,
      processRepository,
      messageBus,
      clock,
      () => InquiryId,
      () =>
        val sequence = paymentSequence.getAndIncrement()
        if sequence == 0 then PaymentId
        else UUID.nameUUIDFromBytes(s"payment-$sequence".getBytes)
    )
    new AccountMessageHandler(accountOperations, messageBus, clock).subscribe()
    new BillPaymentProcessManager(
      billerGateway,
      inquiryRepository,
      processRepository,
      messageBus,
      clock
    ).subscribe()
    Fixture(accountOperations, messageBus, clock, depositService, service)

  private def createBillerGateway(): InMemoryBillerGateway =
    new InMemoryBillerGateway(
      Vector(BillSeed(Biller, Reference1, Reference2, BigDecimal("100.00")))
    )

  private def fund(fixture: Fixture, amount: BigDecimal): Unit =
    requireRight(fixture.depositService.deposit(Account, amount))

  private def inquire(fixture: Fixture): BillPaymentInquiry =
    requireRight(fixture.service.inquire(Account, Biller, Reference1, Reference2)).inquiry

  private def confirm(
      fixture: Fixture,
      inquiry: BillPaymentInquiry
  ): BillPaymentAccepted =
    requireRight(fixture.service.confirm(Account, inquiry.inquiryId.value.toString))

  private def payment(fixture: Fixture, paymentId: BillPaymentId): BillPaymentProcess =
    requireRight(fixture.service.payment(Account, paymentId.value.toString))

  private def accountBalance(fixture: Fixture): BigDecimal =
    val accountId = requireRight(AccountId.from(Account))
    requireRight(fixture.accountOperations.find(accountId)).balance

  private def awaitWorkflow(fixture: Fixture): Unit =
    fixture.messageBus.awaitIdle(Duration.ofSeconds(3)) shouldBe true

  private def requireRight[A](result: Either[?, A]): A =
    result match
      case Right(value) => value
      case Left(error: DomainError) => fail(s"Expected Right, got ${error.code}: ${error.message}")
      case Left(error) => fail(s"Expected Right, got $error")

  private final class MutableClock(initialTime: Instant) extends Clock:
    private val currentTime = new AtomicReference(initialTime)

    def set(time: Instant): Unit = currentTime.set(time)

    override def getZone: ZoneId = ZoneOffset.UTC
    override def withZone(zone: ZoneId): Clock = this
    override def instant(): Instant = currentTime.get()
