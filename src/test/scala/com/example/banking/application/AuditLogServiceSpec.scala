package com.example.banking.application

import java.time.{Clock, Instant, ZoneOffset}
import java.util.UUID

import com.example.banking.domain.AuditLogEntry
import com.example.banking.infrastructure.{
  InMemoryAccountRepository,
  InMemoryAuditLogRepository,
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
