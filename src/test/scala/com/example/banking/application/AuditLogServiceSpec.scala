package com.example.banking.application

import java.time.{Clock, Duration, Instant, ZoneOffset}
import java.util.UUID

import com.example.banking.domain.{AccountId, AuditLogEntry, BankingOperation, Money}
import com.example.banking.infrastructure.{InMemoryAuditLogRepository, LocalMessageBus}
import com.example.banking.ledger.LedgerBackedAccountOperations
import com.example.banking.ledger.infrastructure.InMemoryLedgerRepository
import com.example.billpayment.domain.{
  BillerCode,
  BillPaymentCompleted,
  BillPaymentId,
  BillPaymentInquiryId
}
import com.example.domain.DomainError
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class AuditLogServiceSpec extends AnyFunSuite with Matchers:
  test("completed deposit creates an audit log entry") {
    val occurredAt = Instant.parse("2026-09-29T10:15:30Z")
    val depositId = UUID.fromString("7a4459f6-a1f8-4a63-903b-fbf096a19b62")
    val messageBus = new LocalMessageBus()
    val auditLogRepository = new InMemoryAuditLogRepository()
    val auditLogService = new AuditLogService(messageBus, auditLogRepository)
    val depositService = new DepositService(
      new LedgerBackedAccountOperations(new InMemoryLedgerRepository()),
      messageBus,
      Clock.fixed(occurredAt, ZoneOffset.UTC),
      () => depositId
    )
    auditLogService.subscribe()

    val depositResult = depositService.deposit("account-123", BigDecimal("25.50"))
    messageBus.awaitIdle(Duration.ofSeconds(3)) shouldBe true

    depositResult match
      case Right(result) =>
        auditLogRepository.entries shouldBe Vector(AuditLogEntry.from(result.event))
      case Left(error) => fail(s"Expected a completed deposit, got ${error.code}")
  }

  test("subscribing more than once does not duplicate audit log entries") {
    val messageBus = new LocalMessageBus()
    val auditLogRepository = new InMemoryAuditLogRepository()
    val auditLogService = new AuditLogService(messageBus, auditLogRepository)
    val depositService = new DepositService(
      new LedgerBackedAccountOperations(new InMemoryLedgerRepository()),
      messageBus,
      Clock.systemUTC(),
      () => UUID.randomUUID()
    )
    auditLogService.subscribe()
    auditLogService.subscribe()

    depositService.deposit("account-123", BigDecimal("10.00"))
    messageBus.awaitIdle(Duration.ofSeconds(3)) shouldBe true

    auditLogRepository.entries should have size 1
  }

  test("completed withdrawal creates a withdrawal audit log entry") {
    val messageBus = new LocalMessageBus()
    val accountOperations = new LedgerBackedAccountOperations(new InMemoryLedgerRepository())
    val auditLogRepository = new InMemoryAuditLogRepository()
    val auditLogService = new AuditLogService(messageBus, auditLogRepository)
    val depositService = new DepositService(
      accountOperations,
      messageBus,
      Clock.systemUTC(),
      () => UUID.randomUUID()
    )
    val withdrawService = new WithdrawService(
      accountOperations,
      messageBus,
      Clock.systemUTC(),
      () => UUID.randomUUID()
    )
    auditLogService.subscribe()
    depositService.deposit("account-123", BigDecimal("20.00"))

    val withdrawalResult = withdrawService.withdraw("account-123", BigDecimal("5.00"))
    messageBus.awaitIdle(Duration.ofSeconds(3)) shouldBe true

    withdrawalResult match
      case Right(result) =>
        auditLogRepository.entries.last shouldBe AuditLogEntry.from(result.event)
      case Left(error) => fail(s"Expected a completed withdrawal, got ${error.code}")
  }

  test("completed bill payment creates a bill payment audit log entry") {
    val occurredAt = Instant.parse("2026-09-30T02:00:00Z")
    val messageBus = new LocalMessageBus()
    val auditLogRepository = new InMemoryAuditLogRepository()
    val auditLogService = new AuditLogService(messageBus, auditLogRepository)
    val event = BillPaymentCompleted(
      paymentId = BillPaymentId(UUID.randomUUID()),
      inquiryId = BillPaymentInquiryId(UUID.randomUUID()),
      accountId = requireRight(AccountId.from("account-123")),
      billerCode = requireRight(BillerCode.from("demo-biller")),
      amount = requireRight(Money.positive(BigDecimal("100.00"))),
      resultingBalance = BigDecimal("50.00"),
      billerReceiptCode = "receipt-123",
      occurredAt = occurredAt
    )
    auditLogService.subscribe()
    messageBus.publish(event)
    messageBus.awaitIdle(Duration.ofSeconds(3)) shouldBe true

    val auditEntry = auditLogRepository.entries.last
    auditEntry.transactionId shouldBe event.paymentId.value
    auditEntry.operation shouldBe BankingOperation.BillPayment
    auditEntry.accountId shouldBe event.accountId
    auditEntry.amount shouldBe event.amount
    auditEntry.resultingBalance shouldBe event.resultingBalance
    auditEntry.occurredAt shouldBe event.occurredAt
  }

  private def requireRight[A](result: Either[?, A]): A =
    result match
      case Right(value) => value
      case Left(error: DomainError) => fail(s"Expected Right, got ${error.code}: ${error.message}")
      case Left(error) => fail(s"Expected Right, got $error")
