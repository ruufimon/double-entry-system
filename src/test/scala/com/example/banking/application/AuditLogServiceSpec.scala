package com.example.banking.application

import java.time.{Clock, Instant, ZoneOffset}
import java.util.UUID

import com.example.banking.domain.AuditLogEntry
import com.example.banking.infrastructure.{
  BillSeed,
  InMemoryAccountRepository,
  InMemoryAuditLogRepository,
  InMemoryBillerGateway,
  InMemoryBillPaymentInquiryRepository,
  LocalMessageBus
}
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
      new InMemoryAccountRepository(),
      messageBus,
      Clock.fixed(occurredAt, ZoneOffset.UTC),
      () => depositId
    )
    auditLogService.subscribe()

    val depositResult = depositService.deposit("account-123", BigDecimal("25.50"))

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
      new InMemoryAccountRepository(),
      messageBus,
      Clock.systemUTC(),
      () => UUID.randomUUID()
    )
    auditLogService.subscribe()
    auditLogService.subscribe()

    depositService.deposit("account-123", BigDecimal("10.00"))

    auditLogRepository.entries should have size 1
  }

  test("completed withdrawal creates a withdrawal audit log entry") {
    val messageBus = new LocalMessageBus()
    val accountRepository = new InMemoryAccountRepository()
    val auditLogRepository = new InMemoryAuditLogRepository()
    val auditLogService = new AuditLogService(messageBus, auditLogRepository)
    val depositService = new DepositService(
      accountRepository,
      messageBus,
      Clock.systemUTC(),
      () => UUID.randomUUID()
    )
    val withdrawService = new WithdrawService(
      accountRepository,
      messageBus,
      Clock.systemUTC(),
      () => UUID.randomUUID()
    )
    auditLogService.subscribe()
    depositService.deposit("account-123", BigDecimal("20.00"))

    val withdrawalResult = withdrawService.withdraw("account-123", BigDecimal("5.00"))

    withdrawalResult match
      case Right(result) =>
        auditLogRepository.entries.last shouldBe AuditLogEntry.from(result.event)
      case Left(error) => fail(s"Expected a completed withdrawal, got ${error.code}")
  }

  test("completed bill payment creates a bill payment audit log entry") {
    val occurredAt = Instant.parse("2026-09-30T02:00:00Z")
    val messageBus = new LocalMessageBus()
    val accountRepository = new InMemoryAccountRepository()
    val auditLogRepository = new InMemoryAuditLogRepository()
    val auditLogService = new AuditLogService(messageBus, auditLogRepository)
    val depositService = new DepositService(
      accountRepository,
      messageBus,
      Clock.fixed(occurredAt, ZoneOffset.UTC),
      () => UUID.randomUUID()
    )
    val billPaymentService = new BillPaymentService(
      accountRepository,
      new InMemoryBillerGateway(
        Vector(
          BillSeed("demo-biller", "customer-001", "invoice-001", BigDecimal("100.00"))
        )
      ),
      new InMemoryBillPaymentInquiryRepository(),
      messageBus,
      Clock.fixed(occurredAt, ZoneOffset.UTC),
      () => UUID.randomUUID(),
      () => UUID.randomUUID()
    )
    auditLogService.subscribe()
    depositService.deposit("account-123", BigDecimal("150.00"))
    val inquiryResult = billPaymentService.inquire(
      "account-123",
      "demo-biller",
      "customer-001",
      "invoice-001"
    )

    inquiryResult match
      case Right(inquiry) =>
        billPaymentService.confirm(
          "account-123",
          inquiry.inquiry.inquiryId.value.toString
        ) match
          case Right(result) =>
            auditLogRepository.entries.last shouldBe AuditLogEntry.from(result.event)
          case Left(error) => fail(s"Expected a completed bill payment, got ${error.code}")
      case Left(error) => fail(s"Expected a bill inquiry, got ${error.code}")
  }
