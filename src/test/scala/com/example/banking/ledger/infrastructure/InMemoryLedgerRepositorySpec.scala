package com.example.banking.ledger.infrastructure

import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

import com.example.banking.domain.*
import com.example.banking.infrastructure.InMemoryAccountRepository
import com.example.banking.ledger.*
import com.example.domain.DomainError
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class InMemoryLedgerRepositorySpec extends AnyFunSuite with Matchers:
  private given ExecutionContext = ExecutionContext.global

  private val OccurredAt = Instant.parse("2026-09-30T03:00:00Z")
  private val Account = requireRight(AccountId.from("ledger-account"))
  private val TenBaht = requireRight(Money.positive(BigDecimal("10.00")))

  test("ledger transaction rejects unbalanced debit and credit entries") {
    val result = LedgerTransaction.create(
      UUID.randomUUID(),
      BankingOperation.Deposit,
      Vector(
        LedgerEntry(
          LedgerAccount.Customer(Account),
          LedgerDirection.Credit,
          TenBaht,
          Currency.THB
        ),
        LedgerEntry(
          LedgerAccount.Cash,
          LedgerDirection.Debit,
          requireRight(Money.positive(BigDecimal("9.00"))),
          Currency.THB
        )
      ),
      OccurredAt
    )

    result shouldBe Left(
      LedgerError.UnbalancedTransaction(BigDecimal("9.00"), BigDecimal("10.00"))
    )
  }

  test("business operations append balanced entries and derive the balance") {
    val (repository, operations) = createLedger()

    requireRight(operations.deposit(UUID.randomUUID(), Account, TenBaht, OccurredAt))
    val account = requireRight(
      operations.withdraw(
        UUID.randomUUID(),
        Account,
        requireRight(Money.positive(BigDecimal("3.50"))),
        OccurredAt.plusSeconds(1)
      )
    )

    account.balance shouldBe BigDecimal("6.50")
    requireRight(operations.find(Account)).balance shouldBe BigDecimal("6.50")
    repository.transactions should have size 2
    repository.transactions.foreach { transaction =>
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
  }

  test("bill-payment reversal appends inverse entries and cannot be applied twice") {
    val (repository, operations) = createLedger()
    val paymentId = UUID.randomUUID()
    val reversalId = UUID.randomUUID()
    requireRight(operations.deposit(UUID.randomUUID(), Account, TenBaht, OccurredAt))
    requireRight(
      operations.chargeForBillPayment(paymentId, Account, TenBaht, OccurredAt.plusSeconds(1))
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
    repository.transactions.last.reversesTransactionId shouldBe Some(paymentId)
  }

  test("concurrent withdrawals cannot overdraw an account") {
    val (_, operations) = createLedger()
    requireRight(operations.deposit(UUID.randomUUID(), Account, TenBaht, OccurredAt))
    val start = new CountDownLatch(1)

    val attempts = Vector.fill(2) {
      Future {
        start.await()
        operations.withdraw(UUID.randomUUID(), Account, TenBaht, OccurredAt.plusSeconds(1))
      }
    }
    start.countDown()
    val results = Await.result(Future.sequence(attempts), 3.seconds)

    results.count(_.isRight) shouldBe 1
    results.count(_.isLeft) shouldBe 1
    requireRight(operations.find(Account)).balance shouldBe BigDecimal(0)
  }

  test("account activities expose business effects instead of ledger directions") {
    val (_, operations) = createLedger()
    val depositId = UUID.randomUUID()
    val paymentId = UUID.randomUUID()
    val reversalId = UUID.randomUUID()
    requireRight(operations.deposit(depositId, Account, TenBaht, OccurredAt))
    requireRight(
      operations.chargeForBillPayment(paymentId, Account, TenBaht, OccurredAt.plusSeconds(1))
    )
    requireRight(
      operations.reverseBillPaymentCharge(paymentId, reversalId, OccurredAt.plusSeconds(2))
    )

    val activities = requireRight(operations.activities(Account))

    activities.map(_.transactionId) shouldBe Vector(depositId, paymentId, reversalId)
    activities.map(_.effect) shouldBe Vector(
      BalanceEffect.Increase,
      BalanceEffect.Decrease,
      BalanceEffect.Increase
    )
    activities.map(_.balanceAfter) shouldBe Vector(
      BigDecimal("10.00"),
      BigDecimal("0.00"),
      BigDecimal("10.00")
    )
    activities(1).status shouldBe AccountActivityStatus.Reversed
    activities(2).operation shouldBe BankingOperation.BillPaymentReversal
    activities(2).originalTransactionId shouldBe Some(paymentId)
  }

  test("account overview uses one internally consistent ledger snapshot") {
    val (_, operations) = createLedger()
    requireRight(operations.deposit(UUID.randomUUID(), Account, TenBaht, OccurredAt))
    requireRight(
      operations.withdraw(
        UUID.randomUUID(),
        Account,
        requireRight(Money.positive(BigDecimal("2.50"))),
        OccurredAt.plusSeconds(1)
      )
    )

    val overview = requireRight(operations.overview(Account))

    overview.account.balance shouldBe BigDecimal("7.50")
    overview.activities should have size 2
    overview.activities.last.balanceAfter shouldBe overview.account.balance
  }

  private def createLedger(): (InMemoryLedgerRepository, LedgerBackedAccountOperations) =
    val repository = new InMemoryLedgerRepository()
    val operations = new LedgerBackedAccountOperations(
      repository,
      new InMemoryAccountRepository()
    )
    requireRight(operations.open(Account))
    repository -> operations

  private def requireRight[A](result: Either[?, A]): A =
    result match
      case Right(value) => value
      case Left(error: DomainError) => fail(s"Expected Right, got ${error.code}: ${error.message}")
      case Left(error) => fail(s"Expected Right, got $error")
