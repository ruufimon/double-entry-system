package com.example.banking.ledger.infrastructure

import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

import com.example.banking.domain.*
import com.example.banking.infrastructure.H2AccountRepository
import com.example.banking.ledger.{LedgerBackedAccountOperations, LedgerDirection, LedgerError}
import com.example.banking.ledger.infrastructure.H2LedgerRepository
import com.example.domain.DomainError
import com.example.infrastructure.{Database, PostgresTestContainer}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class PostgresLedgerRepositorySpec extends AnyFunSuite with Matchers with PostgresTestContainer:
  private given ExecutionContext = ExecutionContext.global

  private val OccurredAt = Instant.parse("2026-09-30T03:00:00Z")
  private val TenBaht = requireRight(Money.positive(BigDecimal("10.00")))

  test("PostgreSQL: ledger transactions append balanced entries and derive the balance") {
    val database = createPostgresDatabase()
    try
      val account = requireRight(AccountId.from("postgres-account-1"))
      val (repository, operations) = createLedger(database)
      requireRight(operations.open(account))

      requireRight(operations.deposit(UUID.randomUUID(), account, TenBaht, OccurredAt))
      val result = requireRight(
        operations.withdraw(
          UUID.randomUUID(),
          account,
          requireRight(Money.positive(BigDecimal("3.50"))),
          OccurredAt.plusSeconds(1)
        )
      )

      result.balance shouldBe BigDecimal("6.50")
      requireRight(operations.find(account)).balance shouldBe BigDecimal("6.50")
      repository.transactions should have size 2
      repository.transactions.foreach { transaction =>
        transaction.entries.toVector should have size 2
        val debitTotal = transaction.entries
          .filter(_.direction == LedgerDirection.Debit)
          .map(_.amount.amount)
          .sum
        val creditTotal = transaction.entries
          .filter(_.direction == LedgerDirection.Credit)
          .map(_.amount.amount)
          .sum
        debitTotal shouldBe creditTotal
      }
    finally database.close()
  }

  test("PostgreSQL: opening an existing account keeps its balance and reports it was not created") {
    val database = createPostgresDatabase()
    try
      val account = requireRight(AccountId.from("postgres-account-reopened"))
      val (_, operations) = createLedger(database)
      requireRight(operations.open(account)).created shouldBe true
      requireRight(operations.deposit(UUID.randomUUID(), account, TenBaht, OccurredAt))

      requireRight(operations.open(account)).created shouldBe false

      requireRight(operations.find(account)).balance shouldBe BigDecimal("10.00")
    finally database.close()
  }

  test("PostgreSQL: concurrent withdrawals cannot overdraw an account") {
    val database = createPostgresDatabase()
    try
      val account = requireRight(AccountId.from("postgres-concurrent-1"))
      val (_, operations) = createLedger(database)
      requireRight(operations.open(account))
      requireRight(operations.deposit(UUID.randomUUID(), account, TenBaht, OccurredAt))
      val start = new CountDownLatch(1)

      val attempts = Vector.fill(2) {
        Future {
          start.await()
          operations.withdraw(UUID.randomUUID(), account, TenBaht, OccurredAt.plusSeconds(1))
        }
      }
      start.countDown()
      val results = Await.result(Future.sequence(attempts), 5.seconds)

      results.count(_.isRight) shouldBe 1
      results.count(_.isLeft) shouldBe 1
      requireRight(operations.find(account)).balance shouldBe BigDecimal(0)
    finally database.close()
  }

  test("PostgreSQL: concurrent transfers cannot overdraw or over-credit the same account") {
    val database = createPostgresDatabase()
    try
      val source = requireRight(AccountId.from("postgres-transfer-source"))
      val destination = requireRight(AccountId.from("postgres-transfer-dest"))
      val (_, operations) = createLedger(database)
      requireRight(operations.open(source))
      requireRight(operations.open(destination))
      requireRight(operations.deposit(UUID.randomUUID(), source, TenBaht, OccurredAt))
      val start = new CountDownLatch(1)

      val attempts = Vector.fill(2) {
        Future {
          start.await()
          operations.transfer(
            UUID.randomUUID(),
            source,
            destination,
            TenBaht,
            OccurredAt.plusSeconds(1)
          )
        }
      }
      start.countDown()
      val results = Await.result(Future.sequence(attempts), 5.seconds)

      results.count(_.isRight) shouldBe 1
      results.count(_.isLeft) shouldBe 1
      requireRight(operations.find(source)).balance shouldBe BigDecimal(0)
      requireRight(operations.find(destination)).balance shouldBe BigDecimal("10.00")
    finally database.close()
  }

  test("PostgreSQL: transfer posts one atomic transaction visible to both accounts") {
    val database = createPostgresDatabase()
    try
      val account = requireRight(AccountId.from("postgres-transfer-1"))
      val destination = requireRight(AccountId.from("postgres-transfer-2"))
      val (repository, operations) = createLedger(database)
      requireRight(operations.open(account))
      requireRight(operations.open(destination))
      requireRight(operations.deposit(UUID.randomUUID(), account, TenBaht, OccurredAt))
      val transferId = UUID.randomUUID()

      val accounts = requireRight(
        operations.transfer(
          transferId,
          account,
          destination,
          requireRight(Money.positiveTransfer(BigDecimal("3.50"))),
          OccurredAt.plusSeconds(1)
        )
      )

      accounts.source.balance shouldBe BigDecimal("6.50")
      accounts.destination.balance shouldBe BigDecimal("3.50")
      repository.transactions.last.entries.toVector should have size 2
      val sourceActivity = requireRight(operations.activities(account)).last
      val destinationActivity = requireRight(operations.activities(destination)).last
      sourceActivity.transactionId shouldBe transferId
      sourceActivity.effect shouldBe BalanceEffect.Decrease
      sourceActivity.counterpartyAccountId shouldBe Some(destination)
      destinationActivity.transactionId shouldBe transferId
      destinationActivity.effect shouldBe BalanceEffect.Increase
      destinationActivity.counterpartyAccountId shouldBe Some(account)

      operations.transfer(
        transferId,
        account,
        destination,
        requireRight(Money.positiveTransfer(BigDecimal("1.00"))),
        OccurredAt.plusSeconds(2)
      ) shouldBe Left(LedgerError.DuplicateTransaction(transferId))
      requireRight(operations.find(account)).balance shouldBe BigDecimal("6.50")
      requireRight(operations.find(destination)).balance shouldBe BigDecimal("3.50")
    finally database.close()
  }

  test("PostgreSQL: bill-payment reversal appends inverse entries") {
    val database = createPostgresDatabase()
    try
      val account = requireRight(AccountId.from("postgres-reversal"))
      val (repository, operations) = createLedger(database)
      requireRight(operations.open(account))
      val paymentId = UUID.randomUUID()
      val reversalId = UUID.randomUUID()
      requireRight(operations.deposit(UUID.randomUUID(), account, TenBaht, OccurredAt))
      requireRight(
        operations.chargeForBillPayment(paymentId, account, TenBaht, OccurredAt.plusSeconds(1))
      )

      val reversedAccount = requireRight(
        operations.reverseBillPaymentCharge(paymentId, reversalId, OccurredAt.plusSeconds(2))
      )

      reversedAccount.balance shouldBe BigDecimal("10.00")
      operations.reverseBillPaymentCharge(
        paymentId,
        UUID.randomUUID(),
        OccurredAt.plusSeconds(3)
      ) shouldBe Left(LedgerError.TransactionAlreadyReversed(paymentId))
      repository.transactions should have size 3
      val originalPayment = repository.transactions(1)
      val reversal = repository.transactions.last
      reversal.reversesTransactionId shouldBe Some(paymentId)
      reversal.entries shouldBe originalPayment.entries.map(_.reversed)
    finally database.close()
  }

  test("PostgreSQL: balance adjustment with memo") {
    val database = createPostgresDatabase()
    try
      val account = requireRight(AccountId.from("postgres-adjustment"))
      val (_, operations) = createLedger(database)
      requireRight(operations.open(account))
      requireRight(operations.deposit(UUID.randomUUID(), account, TenBaht, OccurredAt))

      val adjustmentId = AdjustmentId(UUID.randomUUID())
      val adjustmentAmount = requireRight(Money.positive(BigDecimal("5.00")))
      val memo = AdjustmentMemo(
        AdjustmentReason.unsafe("Correction for deposit entry error"),
        AdminActor.unsafe("admin-test")
      )
      val result = operations.adjust(
        adjustmentId,
        account,
        AdjustmentDirection.Credit,
        adjustmentAmount,
        memo,
        None,
        OccurredAt.plusSeconds(1)
      )

      result shouldBe a[Right[?, ?]]
      val adjustment = requireRight(result)
      adjustment.balanceAfter shouldBe BigDecimal("15.00")
    finally database.close()
  }

  test("PostgreSQL: account overview uses one internally consistent ledger snapshot") {
    val database = createPostgresDatabase()
    try
      val account = requireRight(AccountId.from("postgres-overview"))
      val (_, operations) = createLedger(database)
      requireRight(operations.open(account))
      requireRight(operations.deposit(UUID.randomUUID(), account, TenBaht, OccurredAt))
      requireRight(
        operations.withdraw(
          UUID.randomUUID(),
          account,
          requireRight(Money.positive(BigDecimal("2.50"))),
          OccurredAt.plusSeconds(1)
        )
      )

      val overview = requireRight(operations.overview(account))

      overview.account.balance shouldBe BigDecimal("7.50")
      overview.activities should have size 2
      overview.activities.last.balanceAfter shouldBe overview.account.balance
    finally database.close()
  }

  private def createLedger(database: Database): (H2LedgerRepository, LedgerBackedAccountOperations) =
    val repository = new H2LedgerRepository(database)
    val operations = new LedgerBackedAccountOperations(
      repository,
      new H2AccountRepository(database)
    )
    repository -> operations

  private def requireRight[A](result: Either[?, A]): A =
    result match
      case Right(value) => value
      case Left(error: DomainError) => fail(s"Expected Right, got ${error.code}: ${error.message}")
      case Left(error) => fail(s"Expected Right, got $error")
