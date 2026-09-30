package com.example.banking.application

import java.time.{Clock, Duration, Instant, ZoneOffset}
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import com.example.banking.domain.*
import com.example.banking.infrastructure.LocalMessageBus
import com.example.banking.ledger.LedgerBackedAccountOperations
import com.example.banking.ledger.infrastructure.InMemoryLedgerRepository
import com.example.domain.DomainError
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class AccountMessageHandlerSpec extends AnyFunSuite with Matchers:
  private val OccurredAt = Instant.parse("2026-09-30T04:00:00Z")

  test("duplicate bill payment charge requests replay their outcome without duplicate ledger entries") {
    val ledgerRepository = new InMemoryLedgerRepository()
    val accountOperations = new LedgerBackedAccountOperations(ledgerRepository)
    val messageBus = new LocalMessageBus()
    val accountId = requireRight(AccountId.from("account-123"))
    val funding = requireRight(Money.positive(BigDecimal("150.00")))
    val payment = requireRight(Money.positive(BigDecimal("100.00")))
    val paymentId = UUID.randomUUID()
    requireRight(accountOperations.deposit(UUID.randomUUID(), accountId, funding, OccurredAt))
    new AccountMessageHandler(
      accountOperations,
      messageBus,
      Clock.fixed(OccurredAt, ZoneOffset.UTC)
    ).subscribe()
    val completedCount = new AtomicInteger(0)
    messageBus.subscribe {
      case _: BillPaymentChargeCompleted => completedCount.incrementAndGet()
      case _                             => ()
    }
    val request = BillPaymentChargeRequested(paymentId, accountId, payment, OccurredAt)

    messageBus.publish(request)
    messageBus.publish(request)
    messageBus.awaitIdle(Duration.ofSeconds(3)) shouldBe true

    completedCount.get() shouldBe 2
    ledgerRepository.transactions.count(_.transactionId == paymentId) shouldBe 1
    requireRight(accountOperations.find(accountId)).balance shouldBe BigDecimal("50.00")
  }

  private def requireRight[A](result: Either[?, A]): A =
    result match
      case Right(value) => value
      case Left(error: DomainError) => fail(s"Expected Right, got ${error.code}: ${error.message}")
      case Left(error) => fail(s"Expected Right, got $error")
