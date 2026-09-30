package com.example.banking.application

import java.time.{Clock, Instant, ZoneOffset}
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

import com.example.banking.domain.{BankingError, DomainEvent, WithdrawalCompleted}
import com.example.banking.infrastructure.{InMemoryAccountRepository, LocalMessageBus}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class WithdrawServiceSpec extends AnyFunSuite with Matchers:
  test("withdraw deducts funds and publishes WithdrawalCompleted") {
    val occurredAt = Instant.parse("2026-09-29T11:30:00Z")
    val withdrawalId = UUID.fromString("9f7c7448-119f-4a58-b988-14a2992c78b3")
    val accountRepository = new InMemoryAccountRepository()
    val messageBus = new LocalMessageBus()
    val publishedEvent = new AtomicReference[Option[DomainEvent]](None)
    messageBus.subscribe(event => publishedEvent.set(Some(event)))
    val depositService = new DepositService(
      accountRepository,
      messageBus,
      Clock.systemUTC(),
      () => UUID.randomUUID()
    )
    val withdrawService = new WithdrawService(
      accountRepository,
      messageBus,
      Clock.fixed(occurredAt, ZoneOffset.UTC),
      () => withdrawalId
    )
    depositService.deposit("account-123", BigDecimal("50.00"))

    val withdrawalResult = withdrawService.withdraw("account-123", BigDecimal("12.50"))

    withdrawalResult match
      case Right(result) =>
        result.account.balance shouldBe BigDecimal("37.50")
        result.event.withdrawalId.value shouldBe withdrawalId
        result.event.occurredAt shouldBe occurredAt
        publishedEvent.get() shouldBe Some(result.event)
      case Left(error) => fail(s"Expected a completed withdrawal, got ${error.code}")
  }

  test("insufficient funds does not publish WithdrawalCompleted") {
    val accountRepository = new InMemoryAccountRepository()
    val messageBus = new LocalMessageBus()
    val withdrawalEvents = new AtomicReference(Vector.empty[WithdrawalCompleted])
    messageBus.subscribe {
      case event: WithdrawalCompleted => withdrawalEvents.updateAndGet(_ :+ event)
      case _                          => ()
    }
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
    depositService.deposit("account-123", BigDecimal("10.00"))

    val withdrawalResult = withdrawService.withdraw("account-123", BigDecimal("10.01"))

    withdrawalResult shouldBe Left(BankingError.InsufficientFunds(BigDecimal("10.00"), BigDecimal("10.01")))
    withdrawalEvents.get() shouldBe empty
  }
