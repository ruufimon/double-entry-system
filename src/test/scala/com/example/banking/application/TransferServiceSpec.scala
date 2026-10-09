package com.example.banking.application

import java.time.{Clock, Instant, ZoneOffset}
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

import com.example.banking.domain.*
import com.example.banking.infrastructure.{InMemoryAccountRepository, LocalMessageBus}
import com.example.banking.ledger.LedgerBackedAccountOperations
import com.example.banking.ledger.infrastructure.InMemoryLedgerRepository
import com.example.domain.DomainError
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class TransferServiceSpec extends AnyFunSuite with Matchers:
  private val occurredAt = Instant.parse("2026-10-09T04:00:00Z")
  private val sourceId = requireRight(AccountId.from("transfer-source"))
  private val destinationId = requireRight(AccountId.from("transfer-destination"))

  test("transfer atomically updates both accounts and publishes completion") {
    val transferId = UUID.fromString("e850b108-a2d1-4ff0-a38a-da46a4a00665")
    val fixture = createFixture(transferId)
    val events = new AtomicReference(Vector.empty[TransferCompleted])
    fixture.messageBus.subscribe {
      case event: TransferCompleted => events.updateAndGet(_ :+ event)
      case _                        => ()
    }

    val result = fixture.service.transfer(
      sourceId.value,
      destinationId.value,
      BigDecimal("25.50")
    )

    val transfer = requireRight(result)
    transfer.transferId.value shouldBe transferId
    transfer.sourceAccount.balance shouldBe BigDecimal("74.50")
    transfer.destinationAccount.balance shouldBe BigDecimal("25.50")
    requireRight(fixture.operations.find(sourceId)).balance shouldBe BigDecimal("74.50")
    requireRight(fixture.operations.find(destinationId)).balance shouldBe BigDecimal("25.50")
    fixture.messageBus.awaitIdle(java.time.Duration.ofSeconds(3)) shouldBe true
    events.get() shouldBe Vector(transfer.event)
  }

  test("failed transfers leave both accounts unchanged and publish no event") {
    val fixture = createFixture(UUID.randomUUID())
    val events = new AtomicReference(Vector.empty[TransferCompleted])
    fixture.messageBus.subscribe {
      case event: TransferCompleted => events.updateAndGet(_ :+ event)
      case _                        => ()
    }

    fixture.service.transfer(
      sourceId.value,
      destinationId.value,
      BigDecimal("100.01")
    ) shouldBe Left(BankingError.InsufficientFunds(BigDecimal("100.00"), BigDecimal("100.01")))
    fixture.service.transfer(sourceId.value, sourceId.value, BigDecimal("1.00")) shouldBe
      Left(BankingError.SameAccountTransfer)

    requireRight(fixture.operations.find(sourceId)).balance shouldBe BigDecimal("100.00")
    requireRight(fixture.operations.find(destinationId)).balance shouldBe BigDecimal(0)
    events.get() shouldBe empty
  }

  private final case class Fixture(
      operations: LedgerBackedAccountOperations,
      messageBus: LocalMessageBus,
      service: TransferService
  )

  private def createFixture(transferId: UUID): Fixture =
    val operations = new LedgerBackedAccountOperations(
      new InMemoryLedgerRepository(),
      new InMemoryAccountRepository()
    )
    requireRight(operations.open(sourceId))
    requireRight(operations.open(destinationId))
    val funding = requireRight(Money.positive(BigDecimal("100.00")))
    requireRight(operations.deposit(UUID.randomUUID(), sourceId, funding, occurredAt.minusSeconds(1)))
    val messageBus = new LocalMessageBus()
    Fixture(
      operations,
      messageBus,
      new TransferService(
        operations,
        messageBus,
        Clock.fixed(occurredAt, ZoneOffset.UTC),
        () => transferId
      )
    )

  private def requireRight[A](result: Either[?, A]): A =
    result match
      case Right(value) => value
      case Left(error: DomainError) => fail(s"Expected Right, got ${error.code}: ${error.message}")
      case Left(error) => fail(s"Expected Right, got $error")
