package com.example.banking.application

import java.time.{Clock, Duration, Instant, ZoneOffset}
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

import com.example.banking.domain.DomainEvent
import com.example.banking.infrastructure.LocalMessageBus
import com.example.banking.ledger.LedgerBackedAccountOperations
import com.example.banking.ledger.infrastructure.InMemoryLedgerRepository
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class DepositServiceSpec extends AnyFunSuite with Matchers:
  test("deposit returns a DepositCompleted domain event") {
    val occurredAt = Instant.parse("2026-09-29T10:15:30Z")
    val depositId = UUID.fromString("7a4459f6-a1f8-4a63-903b-fbf096a19b62")
    val messageBus = new LocalMessageBus()
    val publishedEvent = new AtomicReference[Option[DomainEvent]](None)
    messageBus.subscribe {
      case event: DomainEvent => publishedEvent.set(Some(event))
      case _                  => ()
    }
    val depositService = new DepositService(
      new LedgerBackedAccountOperations(new InMemoryLedgerRepository()),
      messageBus,
      Clock.fixed(occurredAt, ZoneOffset.UTC),
      () => depositId
    )

    val depositResult = depositService.deposit("account-123", BigDecimal("25.50"))
    messageBus.awaitIdle(Duration.ofSeconds(3)) shouldBe true

    depositResult match
      case Right(result) =>
        result.event.depositId.value shouldBe depositId
        result.event.accountId shouldBe result.account.id
        result.event.amount.amount shouldBe BigDecimal("25.50")
        result.event.resultingBalance shouldBe BigDecimal("25.50")
        result.event.occurredAt shouldBe occurredAt
        publishedEvent.get() shouldBe Some(result.event)
      case Left(error) => fail(s"Expected a completed deposit, got ${error.code}")
  }

  test("invalid deposit does not publish a domain event") {
    val messageBus = new LocalMessageBus()
    val publishedEvent = new AtomicReference[Option[DomainEvent]](None)
    messageBus.subscribe {
      case event: DomainEvent => publishedEvent.set(Some(event))
      case _                  => ()
    }
    val depositService = new DepositService(
      new LedgerBackedAccountOperations(new InMemoryLedgerRepository()),
      messageBus,
      Clock.systemUTC(),
      () => UUID.randomUUID()
    )

    depositService.deposit("account-123", BigDecimal(0)).isLeft shouldBe true
    messageBus.awaitIdle(Duration.ofSeconds(3)) shouldBe true
    publishedEvent.get() shouldBe None
  }
