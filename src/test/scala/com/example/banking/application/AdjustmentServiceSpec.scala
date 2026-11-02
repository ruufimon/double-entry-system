package com.example.banking.application

import java.time.{Clock, Duration, Instant, ZoneOffset}
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

import scala.jdk.CollectionConverters.*

import com.example.banking.domain.*
import com.example.banking.infrastructure.{
  InMemoryAccountRepository,
  InMemoryAuditLogRepository,
  LocalMessageBus
}
import com.example.banking.ledger.LedgerBackedAccountOperations
import com.example.banking.ledger.infrastructure.InMemoryLedgerRepository
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class AdjustmentServiceSpec extends AnyFunSuite with Matchers:
  private val OccurredAt = Instant.parse("2026-10-29T04:00:00Z")

  test("a valid adjustment is posted, published, and audited") {
    val fixture = Fixture()
    val key = UUID.randomUUID()

    val result = fixture.service.adjust(fixture.command(idempotencyKey = Some(key.toString)))
    fixture.messageBus.awaitIdle(Duration.ofSeconds(3)) shouldBe true

    val adjustment = result.fold(error => fail(error.code), identity)
    adjustment.adjustmentId shouldBe AdjustmentId(key)
    adjustment.direction shouldBe AdjustmentDirection.Credit
    adjustment.amount.amount shouldBe BigDecimal("5.00")
    adjustment.memo.reason.value shouldBe "Rounding fix"
    adjustment.memo.actor.value shouldBe "ops-alice"
    adjustment.balanceAfter shouldBe BigDecimal("15.00")
    adjustment.occurredAt shouldBe OccurredAt
    fixture.published shouldBe Vector(BalanceAdjusted(adjustment))
    fixture.auditLog.entries shouldBe Vector(
      AuditLogEntry(
        key,
        BankingOperation.Adjustment,
        fixture.accountId,
        adjustment.amount,
        BigDecimal("15.00"),
        OccurredAt
      )
    )
    fixture.service.adjustments("service-account").map(_.map(_.adjustmentId)) shouldBe
      Right(Vector(AdjustmentId(key)))
  }

  test("each invalid field is rejected without posting or publishing") {
    val fixture = Fixture()
    val cases = Vector(
      fixture.command(actor = None) -> "missing_admin_actor",
      fixture.command(idempotencyKey = Some("nope")) -> "invalid_idempotency_key",
      fixture.command(accountId = "bad account") -> "invalid_account_id",
      fixture.command(direction = Some("up")) -> "invalid_adjustment_direction",
      fixture.command(amount = None) -> "invalid_adjustment_amount",
      fixture.command(amount = Some(BigDecimal("0"))) -> "invalid_adjustment_amount",
      fixture.command(reason = Some(" ")) -> "invalid_adjustment_reason"
    )

    cases.foreach { case (command, expectedCode) =>
      withClue(command.toString) {
        fixture.service.adjust(command).left.map(_.code) shouldBe Left(expectedCode)
      }
    }
    fixture.messageBus.awaitIdle(Duration.ofSeconds(3)) shouldBe true
    fixture.published shouldBe empty
    fixture.operations.find(fixture.accountId).map(_.balance) shouldBe Right(BigDecimal("10.00"))
  }

  private final case class Fixture():
    val messageBus = new LocalMessageBus()
    val auditLog = new InMemoryAuditLogRepository()
    val operations = new LedgerBackedAccountOperations(
      new InMemoryLedgerRepository(),
      new InMemoryAccountRepository()
    )
    val accountId: AccountId = AccountId.from("service-account").getOrElse(fail("valid ID"))
    private val events = new ConcurrentLinkedQueue[BalanceAdjusted]()
    messageBus.subscribe {
      case event: BalanceAdjusted => events.add(event)
      case _                      => ()
    }
    new AuditLogService(messageBus, auditLog).subscribe()
    operations.open(accountId)
    operations.deposit(
      UUID.randomUUID(),
      accountId,
      Money.of(BigDecimal("10.00")),
      OccurredAt.minusSeconds(60)
    )
    val service = new AdjustmentService(
      operations,
      messageBus,
      Clock.fixed(OccurredAt, ZoneOffset.UTC)
    )

    def published: Vector[BalanceAdjusted] = events.asScala.toVector

    def command(
        accountId: String = "service-account",
        direction: Option[String] = Some("credit"),
        amount: Option[BigDecimal] = Some(BigDecimal("5.00")),
        reason: Option[String] = Some("Rounding fix"),
        actor: Option[String] = Some("ops-alice"),
        idempotencyKey: Option[String] = Some(UUID.randomUUID().toString),
        adjustsTransactionId: Option[UUID] = None
    ): AdjustmentCommand =
      AdjustmentCommand(
        accountId,
        direction,
        amount,
        reason,
        actor,
        idempotencyKey,
        adjustsTransactionId
      )
