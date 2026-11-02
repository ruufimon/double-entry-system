package com.example.banking.ledger

import java.time.{Instant, YearMonth}
import java.util.UUID

import cats.data.NonEmptyVector
import com.example.banking.domain.*
import com.example.banking.infrastructure.{H2AccountRepository, InMemoryAccountRepository}
import com.example.banking.ledger.infrastructure.{H2LedgerRepository, InMemoryLedgerRepository}
import com.example.infrastructure.Database
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class LedgerActivityOrderingSpec extends AnyFunSuite with Matchers:
  private val Customer = requireRight(AccountId.from("ordered-account"))
  private val Earliest = Instant.parse("2026-10-31T16:59:59Z")
  private val Latest = Instant.parse("2026-10-31T17:00:05Z")
  private val CreditedMonth = YearMonth.of(2026, 9)
  private val DepositAt = Instant.parse("2026-09-10T09:00:00Z")
  private val InterestAt = Instant.parse("2026-09-30T23:59:59Z")
  private val WithdrawalAt = Instant.parse("2026-10-05T09:00:00Z")

  Vector("in-memory" -> (() => inMemoryFixture()), "H2" -> (() => h2Fixture())).foreach {
    case (name, newFixture) =>
      def newOperations(): LedgerBackedAccountOperations = newFixture().operations

      test(s"$name: activities posted in chronological order stay oldest-first") {
        val operations = newOperations()
        operations.open(Customer)
        deposit(operations, "10.00", Earliest)
        deposit(operations, "20.00", Latest)

        val activities = requireRight(operations.activities(Customer))

        activities.map(_.occurredAt) shouldBe Vector(Earliest, Latest)
        activities.map(_.balanceAfter) shouldBe Vector(BigDecimal("10.00"), BigDecimal("30.00"))
      }

      test(s"$name: a backdated transaction is ordered by when it occurred, not when it was posted") {
        val operations = newOperations()
        operations.open(Customer)
        deposit(operations, "20.00", Latest)
        deposit(operations, "10.00", Earliest)

        val activities = requireRight(operations.activities(Customer))
        val overview = requireRight(operations.overview(Customer))

        activities.map(_.occurredAt) shouldBe Vector(Earliest, Latest)
        activities.map(_.balanceAfter) shouldBe Vector(BigDecimal("10.00"), BigDecimal("30.00"))
        overview.activities.last.occurredAt shouldBe Latest
        overview.account.balance shouldBe BigDecimal("30.00")
      }

      test(s"$name: activities with the same timestamp keep their posting order") {
        val operations = newOperations()
        operations.open(Customer)
        val firstId = deposit(operations, "10.00", Earliest)
        val secondId = deposit(operations, "20.00", Earliest)
        val thirdId = deposit(operations, "30.00", Earliest)

        requireRight(operations.activities(Customer)).map(_.transactionId) shouldBe
          Vector(firstId, secondId, thirdId)
      }

      test(s"$name: a backdated interest credit keeps activities consistent with the balance") {
        val fixture = newFixture()
        val operations = fixture.operations
        operations.open(Customer)
        deposit(operations, "100.00", DepositAt)
        requireRight(
          operations.withdraw(
            UUID.randomUUID(),
            Customer,
            requireRight(Money.positive(BigDecimal("30.00"))),
            WithdrawalAt
          )
        )

        val credit = interestCredit("1.25", InterestAt)
        requireRight(fixture.ledger.postInterestCredit(credit, Customer, CreditedMonth)) shouldBe true

        val activities = requireRight(operations.activities(Customer))
        val balance = requireRight(operations.find(Customer)).balance
        val signedSum = activities.map { activity =>
          activity.effect match
            case BalanceEffect.Increase => activity.amount
            case BalanceEffect.Decrease => -activity.amount
        }.sum

        // The credit was posted last but dated before the withdrawal, so it must sort between.
        activities.map(_.occurredAt) shouldBe Vector(DepositAt, InterestAt, WithdrawalAt)
        balance shouldBe BigDecimal("71.25")
        activities.last.balanceAfter shouldBe balance
        signedSum shouldBe balance
      }
  }

  private final case class Fixture(
      ledger: LedgerRepository,
      operations: LedgerBackedAccountOperations
  )

  private def inMemoryFixture(): Fixture =
    val ledger = new InMemoryLedgerRepository()
    Fixture(ledger, new LedgerBackedAccountOperations(ledger, new InMemoryAccountRepository()))

  private def h2Fixture(): Fixture =
    val database = Database.open(Database.h2MemoryUrl(s"ledger-ordering-${UUID.randomUUID()}"))
    val ledger = new H2LedgerRepository(database)
    Fixture(ledger, new LedgerBackedAccountOperations(ledger, new H2AccountRepository(database)))

  private def interestCredit(amount: String, occurredAt: Instant): LedgerTransaction =
    val money = requireRight(Money.positive(BigDecimal(amount)))
    requireRight(
      LedgerTransaction.create(
        UUID.randomUUID(),
        BankingOperation.InterestCredit,
        NonEmptyVector.of(
          LedgerEntry(LedgerAccount.InterestExpense, LedgerDirection.Debit, money, Currency.THB),
          LedgerEntry(LedgerAccount.Customer(Customer), LedgerDirection.Credit, money, Currency.THB)
        ),
        occurredAt
      )
    )

  private def deposit(
      operations: LedgerBackedAccountOperations,
      amount: String,
      occurredAt: Instant
  ): UUID =
    val transactionId = UUID.randomUUID()
    requireRight(
      operations.deposit(
        transactionId,
        Customer,
        requireRight(Money.positive(BigDecimal(amount))),
        occurredAt
      )
    )
    transactionId

  private def requireRight[E, A](result: Either[E, A]): A =
    result.fold(error => fail(s"Expected Right, got $error"), identity)
