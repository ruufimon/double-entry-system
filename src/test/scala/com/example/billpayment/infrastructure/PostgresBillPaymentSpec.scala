package com.example.billpayment.infrastructure

import java.time.{Clock, Duration, Instant}
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

import com.example.banking.application.{AccountMessageHandler, DepositService}
import com.example.banking.domain.AccountId
import com.example.banking.infrastructure.{H2AccountRepository, LocalMessageBus}
import com.example.banking.ledger.LedgerBackedAccountOperations
import com.example.banking.ledger.infrastructure.H2LedgerRepository
import com.example.billpayment.application.{BillPaymentProcessManager, BillPaymentService}
import com.example.billpayment.domain.*
import com.example.billpayment.ports.BillerGateway
import com.example.domain.DomainError
import com.example.infrastructure.{Database, PostgresTestContainer}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class PostgresBillPaymentSpec extends AnyFunSuite with Matchers with PostgresTestContainer:
  private val Account = "postgres-bill-payer"
  private val Biller = "demo-biller"
  private val Reference1 = "customer-001"
  private val Reference2 = "invoice-001"

  test("PostgreSQL: inquiry and confirm pay the bill and debit the account") {
    withFixture(biller()) { fixture =>
      fixture.fund(BigDecimal("150.00"))
      val inquiry = fixture.inquire()

      val accepted = fixture.confirm(inquiry)
      fixture.awaitWorkflow()

      val paid = fixture.payment(accepted.process.paymentId)
      paid.status shouldBe BillPaymentStatus.Completed
      paid.resultingBalance shouldBe Some(BigDecimal("50.00"))
      paid.billerReceipt should not be empty
      fixture.balance shouldBe BigDecimal("50.00")
    }
  }

  test("PostgreSQL: a failed settlement records the failure, refunds, and can be retried") {
    val delegate = biller()
    val failSettlement = new AtomicBoolean(true)
    val failingOnce = new BillerGateway:
      override def fetchDebt(
          billerCode: BillerCode,
          referenceCode1: BillerReference,
          referenceCode2: BillerReference
      ): Either[BillPaymentError, BillerDebt] =
        delegate.fetchDebt(billerCode, referenceCode1, referenceCode2)

      override def settle(
          debt: BillerDebt,
          paymentId: BillPaymentId,
          paidAt: Instant
      ): Either[BillPaymentError, BillerReceipt] =
        if failSettlement.getAndSet(false) then
          Left(BillPaymentError.SettlementFailed("Biller is temporarily unavailable"))
        else delegate.settle(debt, paymentId, paidAt)

    withFixture(failingOnce) { fixture =>
      fixture.fund(BigDecimal("150.00"))
      val inquiry = fixture.inquire()

      val failedAttempt = fixture.confirm(inquiry)
      fixture.awaitWorkflow()

      val failed = fixture.payment(failedAttempt.process.paymentId)
      failed.status shouldBe BillPaymentStatus.Failed
      failed.failure should not be empty
      failed.billerReceipt shouldBe empty
      fixture.balance shouldBe BigDecimal("150.00")

      val retry = fixture.confirm(inquiry)
      fixture.awaitWorkflow()

      fixture.payment(retry.process.paymentId).status shouldBe BillPaymentStatus.Completed
      fixture.balance shouldBe BigDecimal("50.00")
    }
  }

  private def biller(): InMemoryBillerGateway =
    new InMemoryBillerGateway(Vector(BillSeed(Biller, Reference1, Reference2, BigDecimal("100.00"))))

  private def withFixture(gateway: BillerGateway)(test: Fixture => Unit): Unit =
    val database = createPostgresDatabase()
    val messageBus = new LocalMessageBus()
    try test(new Fixture(database, messageBus, gateway))
    finally
      try messageBus.close()
      finally database.close()

  private final class Fixture(database: Database, messageBus: LocalMessageBus, gateway: BillerGateway):
    private val clock = Clock.systemUTC()
    private val accountId = requireRight(AccountId.from(Account))
    private val operations = new LedgerBackedAccountOperations(
      new H2LedgerRepository(database),
      new H2AccountRepository(database)
    )
    private val inquiries = new H2BillPaymentInquiryRepository(database)
    private val processes = new H2BillPaymentProcessRepository(database)
    private val deposits = new DepositService(operations, messageBus, clock, () => UUID.randomUUID())
    private val service = new BillPaymentService(
      gateway,
      inquiries,
      processes,
      messageBus,
      clock,
      () => UUID.randomUUID(),
      () => UUID.randomUUID()
    )
    new AccountMessageHandler(operations, messageBus, clock).subscribe()
    new BillPaymentProcessManager(gateway, inquiries, processes, messageBus, clock).subscribe()

    def fund(amount: BigDecimal): Unit =
      requireRight(operations.open(accountId))
      requireRight(deposits.deposit(Account, amount))

    def inquire(): BillPaymentInquiry =
      requireRight(service.inquire(Account, Biller, Reference1, Reference2)).inquiry

    def confirm(inquiry: BillPaymentInquiry) =
      requireRight(service.confirm(Account, inquiry.inquiryId.value.toString))

    def payment(paymentId: BillPaymentId): BillPaymentProcess =
      requireRight(service.payment(Account, paymentId.value.toString))

    def balance: BigDecimal = requireRight(operations.find(accountId)).balance

    def awaitWorkflow(): Unit = messageBus.awaitIdle(Duration.ofSeconds(5)) shouldBe true

  private def requireRight[A](result: Either[?, A]): A =
    result match
      case Right(value) => value
      case Left(error: DomainError) => fail(s"Expected Right, got ${error.code}: ${error.message}")
      case Left(error) => fail(s"Expected Right, got $error")
