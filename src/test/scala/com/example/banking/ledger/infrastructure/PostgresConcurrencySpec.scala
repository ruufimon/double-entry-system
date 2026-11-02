package com.example.banking.ledger.infrastructure

import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

import com.example.banking.domain.*
import com.example.banking.infrastructure.H2AccountRepository
import com.example.banking.ledger.LedgerBackedAccountOperations
import com.example.domain.DomainError
import com.example.infrastructure.{Database, PostgresTestContainer}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class PostgresConcurrencySpec extends AnyFunSuite with Matchers with PostgresTestContainer:
  private given ExecutionContext = ExecutionContext.global

  private val OccurredAt = Instant.parse("2026-09-30T03:00:00Z")

  test("PostgreSQL: ten concurrent transfers on the same account never overdraw") {
    val database = createPostgresDatabase()
    try
      val source = requireRight(AccountId.from("postgres-race-source"))
      val destinations = (1 to 10).map(i => requireRight(AccountId.from(s"postgres-race-dest-$i")))
      val operations = createLedger(database)
      requireRight(operations.open(source))
      destinations.foreach(dest => requireRight(operations.open(dest)))

      val initialAmount = BigDecimal("100.00")
      requireRight(
        operations.deposit(
          UUID.randomUUID(),
          source,
          requireRight(Money.positive(initialAmount)),
          OccurredAt
        )
      )

      val transferAmount = requireRight(Money.positiveTransfer(BigDecimal("15.00")))
      val start = new CountDownLatch(1)

      val attempts = destinations.map { destination =>
        Future {
          start.await()
          operations.transfer(
            UUID.randomUUID(),
            source,
            destination,
            transferAmount,
            OccurredAt.plusSeconds(1)
          )
        }
      }
      start.countDown()
      val results = Await.result(Future.sequence(attempts), 10.seconds)

      val successCount = results.count(_.isRight)
      val failureCount = results.count(_.isLeft)
      successCount shouldBe 6
      failureCount shouldBe 4
      val sourceBalance = requireRight(operations.find(source)).balance
      sourceBalance shouldBe BigDecimal("10.00")
      val totalDestinationBalance = destinations.map { dest =>
        requireRight(operations.find(dest)).balance
      }.sum
      totalDestinationBalance shouldBe BigDecimal("90.00")
      (sourceBalance + totalDestinationBalance) shouldBe initialAmount
    finally database.close()
  }

  test("PostgreSQL: concurrent deposits and withdrawals maintain consistency") {
    val database = createPostgresDatabase()
    try
      val account = requireRight(AccountId.from("postgres-mixed-ops"))
      val operations = createLedger(database)
      requireRight(operations.open(account))
      requireRight(
        operations.deposit(
          UUID.randomUUID(),
          account,
          requireRight(Money.positive(BigDecimal("50.00"))),
          OccurredAt
        )
      )

      val start = new CountDownLatch(1)
      val amount = requireRight(Money.positive(BigDecimal("10.00")))

      val deposits = Vector.fill(3) {
        Future {
          start.await()
          operations.deposit(UUID.randomUUID(), account, amount, OccurredAt.plusSeconds(1))
        }
      }
      val withdrawals = Vector.fill(5) {
        Future {
          start.await()
          operations.withdraw(UUID.randomUUID(), account, amount, OccurredAt.plusSeconds(1))
        }
      }

      start.countDown()
      val results = Await.result(Future.sequence(deposits ++ withdrawals), 10.seconds)

      val successfulDeposits = results.take(3).count(_.isRight)
      val successfulWithdrawals = results.drop(3).count(_.isRight)
      val expectedBalance = BigDecimal("50.00") + (successfulDeposits * 10) - (successfulWithdrawals * 10)
      val actualBalance = requireRight(operations.find(account)).balance

      actualBalance shouldBe expectedBalance
      actualBalance should be >= BigDecimal(0)
    finally database.close()
  }

  test("PostgreSQL: idempotent transaction IDs prevent duplicate postings under concurrency") {
    val database = createPostgresDatabase()
    try
      val account = requireRight(AccountId.from("postgres-idempotency"))
      val operations = createLedger(database)
      requireRight(operations.open(account))
      requireRight(
        operations.deposit(
          UUID.randomUUID(),
          account,
          requireRight(Money.positive(BigDecimal("100.00"))),
          OccurredAt
        )
      )

      val sharedTransactionId = UUID.randomUUID()
      val amount = requireRight(Money.positive(BigDecimal("10.00")))
      val start = new CountDownLatch(1)

      val attempts = Vector.fill(5) {
        Future {
          start.await()
          operations.withdraw(sharedTransactionId, account, amount, OccurredAt.plusSeconds(1))
        }
      }

      start.countDown()
      val results = Await.result(Future.sequence(attempts), 5.seconds)

      results.count(_.isRight) shouldBe 1
      results.count(_.isLeft) shouldBe 4
      requireRight(operations.find(account)).balance shouldBe BigDecimal("90.00")
    finally database.close()
  }

  private def createLedger(database: Database): LedgerBackedAccountOperations =
    new LedgerBackedAccountOperations(
      new H2LedgerRepository(database),
      new H2AccountRepository(database)
    )

  private def requireRight[A](result: Either[?, A]): A =
    result match
      case Right(value) => value
      case Left(error: DomainError) => fail(s"Expected Right, got ${error.code}: ${error.message}")
      case Left(error) => fail(s"Expected Right, got $error")
