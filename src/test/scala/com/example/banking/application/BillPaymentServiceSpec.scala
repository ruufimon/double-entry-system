package com.example.banking.application

import java.time.{Clock, Instant, ZoneId, ZoneOffset}
import java.util.UUID
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

import com.example.banking.domain.{
  AccountId,
  BankingError,
  BillerCode,
  BillerDebt,
  BillerReceipt,
  BillerReference,
  BillPaymentCompleted,
  BillPaymentId,
  DomainEvent
}
import com.example.banking.infrastructure.{
  BillSeed,
  InMemoryAccountRepository,
  InMemoryBillerGateway,
  InMemoryBillPaymentInquiryRepository,
  LocalMessageBus
}
import com.example.banking.ports.BillerGateway
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class BillPaymentServiceSpec extends AnyFunSuite with Matchers:
  private given ExecutionContext = ExecutionContext.global

  private val Account = "account-123"
  private val Biller = "demo-biller"
  private val Reference1 = "customer-001"
  private val Reference2 = "invoice-001"
  private val InquiryId = UUID.fromString("ac7d454f-f960-423c-a4f8-94f76e71ad24")
  private val PaymentId = UUID.fromString("54834b9b-a26e-4b52-917c-0e62270e49da")
  private val StartTime = Instant.parse("2026-09-30T02:00:00Z")

  test("inquiry returns the current debt with a five-minute expiry") {
    val fixture = createFixture()
    fund(fixture, BigDecimal("150.00"))

    val result = requireRight(fixture.service.inquire(Account, Biller, Reference1, Reference2))

    result.inquiry.inquiryId.value shouldBe InquiryId
    result.inquiry.debt.amount.amount shouldBe BigDecimal("100.00")
    result.inquiry.expiresAt shouldBe StartTime.plusSeconds(300)
  }

  test("inquiry rejects a blank biller reference") {
    val fixture = createFixture()
    fund(fixture, BigDecimal("150.00"))

    fixture.service.inquire(Account, Biller, "   ", Reference2) shouldBe Left(
      BankingError.InvalidBillerReference("referenceCode1")
    )
  }

  test("confirmation deducts the quoted debt and publishes BillPaymentCompleted") {
    val fixture = createFixture()
    val publishedEvent = new AtomicReference[Option[DomainEvent]](None)
    fixture.messageBus.subscribe(event => publishedEvent.set(Some(event)))
    fund(fixture, BigDecimal("150.00"))
    val inquiry = inquire(fixture)

    val result = requireRight(fixture.service.confirm(Account, inquiry.inquiryId.value.toString))

    result.account.balance shouldBe BigDecimal("50.00")
    result.event.paymentId.value shouldBe PaymentId
    result.event.billerReceiptCode should include(PaymentId.toString)
    publishedEvent.get() shouldBe Some(result.event)
  }

  test("insufficient funds leaves the inquiry available for retry") {
    val fixture = createFixture()
    fund(fixture, BigDecimal("50.00"))
    val inquiry = inquire(fixture)

    fixture.service.confirm(Account, inquiry.inquiryId.value.toString) shouldBe Left(
      BankingError.InsufficientFunds(BigDecimal("50.00"), BigDecimal("100.00"))
    )
    fund(fixture, BigDecimal("50.00"))

    requireRight(fixture.service.confirm(Account, inquiry.inquiryId.value.toString)).account.balance shouldBe BigDecimal(0)
  }

  test("expired inquiry cannot be confirmed") {
    val fixture = createFixture()
    fund(fixture, BigDecimal("150.00"))
    val inquiry = inquire(fixture)
    fixture.clock.set(StartTime.plusSeconds(300))

    fixture.service.confirm(Account, inquiry.inquiryId.value.toString) shouldBe Left(
      BankingError.BillPaymentInquiryExpired
    )
  }

  test("completed inquiry cannot be confirmed twice and paid bill is no longer payable") {
    val fixture = createFixture()
    fund(fixture, BigDecimal("250.00"))
    val inquiry = inquire(fixture)
    requireRight(fixture.service.confirm(Account, inquiry.inquiryId.value.toString))
    fixture.clock.set(StartTime.plusSeconds(600))

    fixture.service.confirm(Account, inquiry.inquiryId.value.toString) shouldBe Left(
      BankingError.BillPaymentAlreadyCompleted
    )
    fixture.service.inquire(Account, Biller, Reference1, Reference2) shouldBe Left(
      BankingError.BillNotPayable
    )
  }

  test("settlement failure refunds the account and leaves the inquiry retryable") {
    val delegateGateway = createBillerGateway()
    val failSettlement = new AtomicBoolean(true)
    val failingGateway = new BillerGateway:
      override def fetchDebt(
          billerCode: BillerCode,
          referenceCode1: BillerReference,
          referenceCode2: BillerReference
      ): Either[BankingError, BillerDebt] =
        delegateGateway.fetchDebt(billerCode, referenceCode1, referenceCode2)

      override def settle(
          debt: BillerDebt,
          paymentId: BillPaymentId,
          paidAt: Instant
      ): Either[BankingError, BillerReceipt] =
        if failSettlement.getAndSet(false) then
          Left(BankingError.BillerSettlementFailed("Biller is temporarily unavailable"))
        else
          delegateGateway.settle(debt, paymentId, paidAt)

    val fixture = createFixture(failingGateway)
    val completedEvents = new AtomicReference(Vector.empty[BillPaymentCompleted])
    fixture.messageBus.subscribe {
      case event: BillPaymentCompleted => completedEvents.updateAndGet(_ :+ event)
      case _                           => ()
    }
    fund(fixture, BigDecimal("150.00"))
    val inquiry = inquire(fixture)

    fixture.service.confirm(Account, inquiry.inquiryId.value.toString) shouldBe Left(
      BankingError.BillerSettlementFailed("Biller is temporarily unavailable")
    )
    accountBalance(fixture) shouldBe BigDecimal("150.00")
    completedEvents.get() shouldBe empty

    requireRight(fixture.service.confirm(Account, inquiry.inquiryId.value.toString)).account.balance shouldBe BigDecimal("50.00")
  }

  test("inquiry is bound to its originating account") {
    val fixture = createFixture()
    fund(fixture, BigDecimal("150.00"))
    fixture.depositService.deposit("other-account", BigDecimal("150.00"))
    val inquiry = inquire(fixture)

    fixture.service.confirm("other-account", inquiry.inquiryId.value.toString) shouldBe Left(
      BankingError.BillPaymentInquiryNotFound
    )
  }

  test("simultaneous confirmation rejects the second request as in progress") {
    val delegateGateway = createBillerGateway()
    val settlementStarted = new CountDownLatch(1)
    val allowSettlement = new CountDownLatch(1)
    val blockingGateway = new BillerGateway:
      override def fetchDebt(
          billerCode: BillerCode,
          referenceCode1: BillerReference,
          referenceCode2: BillerReference
      ): Either[BankingError, BillerDebt] =
        delegateGateway.fetchDebt(billerCode, referenceCode1, referenceCode2)

      override def settle(
          debt: BillerDebt,
          paymentId: BillPaymentId,
        paidAt: Instant
      ): Either[BankingError, BillerReceipt] =
        settlementStarted.countDown()
        allowSettlement.await(3, TimeUnit.SECONDS)
        delegateGateway.settle(debt, paymentId, paidAt)

    val fixture = createFixture(blockingGateway)
    fund(fixture, BigDecimal("250.00"))
    val inquiry = inquire(fixture)
    val firstConfirmation = Future {
      fixture.service.confirm(Account, inquiry.inquiryId.value.toString)
    }
    settlementStarted.await(3, TimeUnit.SECONDS) shouldBe true

    val secondConfirmation = fixture.service.confirm(Account, inquiry.inquiryId.value.toString)
    allowSettlement.countDown()

    secondConfirmation shouldBe Left(BankingError.BillPaymentInProgress)
    requireRight(Await.result(firstConfirmation, 3.seconds)).account.balance shouldBe BigDecimal("150.00")
  }

  private final case class Fixture(
      accountRepository: InMemoryAccountRepository,
      messageBus: LocalMessageBus,
      clock: MutableClock,
      depositService: DepositService,
      service: BillPaymentService
  )

  private def createFixture(
      billerGateway: BillerGateway = createBillerGateway()
  ): Fixture =
    val accountRepository = new InMemoryAccountRepository()
    val messageBus = new LocalMessageBus()
    val clock = new MutableClock(StartTime)
    val depositService = new DepositService(
      accountRepository,
      messageBus,
      clock,
      () => UUID.randomUUID()
    )
    val service = new BillPaymentService(
      accountRepository,
      billerGateway,
      new InMemoryBillPaymentInquiryRepository(),
      messageBus,
      clock,
      () => InquiryId,
      () => PaymentId
    )
    Fixture(accountRepository, messageBus, clock, depositService, service)

  private def createBillerGateway(): InMemoryBillerGateway =
    new InMemoryBillerGateway(
      Vector(BillSeed(Biller, Reference1, Reference2, BigDecimal("100.00")))
    )

  private def fund(fixture: Fixture, amount: BigDecimal): Unit =
    requireRight(fixture.depositService.deposit(Account, amount))

  private def inquire(fixture: Fixture) =
    requireRight(fixture.service.inquire(Account, Biller, Reference1, Reference2)).inquiry

  private def accountBalance(fixture: Fixture): BigDecimal =
    val accountId = requireRight(AccountId.from(Account))
    requireRight(fixture.accountRepository.find(accountId)).balance

  private def requireRight[A](result: Either[BankingError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"Expected Right, got ${error.code}: ${error.message}")

  private final class MutableClock(initialTime: Instant) extends Clock:
    private val currentTime = new AtomicReference(initialTime)

    def set(time: Instant): Unit = currentTime.set(time)

    override def getZone: ZoneId = ZoneOffset.UTC
    override def withZone(zone: ZoneId): Clock = this
    override def instant(): Instant = currentTime.get()
