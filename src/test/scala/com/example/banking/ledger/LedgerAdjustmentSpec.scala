package com.example.banking.ledger

import java.time.Instant
import java.util.UUID

import cats.data.NonEmptyVector
import com.example.banking.domain.*
import com.example.banking.infrastructure.{H2AccountRepository, InMemoryAccountRepository}
import com.example.banking.ledger.infrastructure.{H2LedgerRepository, InMemoryLedgerRepository}
import com.example.domain.DomainError
import com.example.infrastructure.Database
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class LedgerAdjustmentSpec extends AnyFunSuite with Matchers:
  private val OccurredAt = Instant.parse("2026-10-29T03:00:00Z")
  private val Customer = requireRight(AccountId.from("adjusted-account"))
  private val Other = requireRight(AccountId.from("other-account"))
  private val Memo = AdjustmentMemo(
    requireRight(AdjustmentReason.from(Some("Deposit keyed wrong"))),
    requireRight(AdminActor.from(Some("ops-alice")))
  )

  test("an adjustment must balance the customer against the adjustments account") {
    val result = LedgerTransaction.create(
      UUID.randomUUID(),
      BankingOperation.Adjustment,
      NonEmptyVector.of(
        LedgerEntry(LedgerAccount.Customer(Customer), LedgerDirection.Credit, money("1.00"), Currency.THB),
        LedgerEntry(LedgerAccount.Cash, LedgerDirection.Debit, money("1.00"), Currency.THB)
      ),
      OccurredAt
    )

    result shouldBe Left(
      LedgerError.InvalidTransaction("An adjustment must post against the adjustments account")
    )
  }

  test("only an adjustment may reference an adjusted transaction") {
    val result = LedgerTransaction.create(
      UUID.randomUUID(),
      BankingOperation.Deposit,
      NonEmptyVector.of(
        LedgerEntry(LedgerAccount.Customer(Customer), LedgerDirection.Credit, money("1.00"), Currency.THB),
        LedgerEntry(LedgerAccount.Cash, LedgerDirection.Debit, money("1.00"), Currency.THB)
      ),
      OccurredAt,
      adjustsTransactionId = Some(UUID.randomUUID())
    )

    result shouldBe Left(
      LedgerError.InvalidTransaction("Only an adjustment may reference an adjusted transaction")
    )
  }

  Vector("in-memory" -> (() => inMemoryOperations()), "H2" -> (() => h2Operations())).foreach {
    case (name, newOperations) =>
      test(s"$name: a credit adjustment linked to a deposit raises the balance and keeps its memo") {
        val operations = newOperations()
        val depositId = fund(operations, Customer, "100.00")
        val adjustmentId = AdjustmentId(UUID.randomUUID())

        val adjustment = requireRight(
          operations.adjust(
            adjustmentId,
            Customer,
            AdjustmentDirection.Credit,
            money("50.00"),
            Memo,
            Some(depositId),
            OccurredAt.plusSeconds(60)
          )
        )

        adjustment shouldBe BalanceAdjustment(
          adjustmentId,
          Customer,
          AdjustmentDirection.Credit,
          money("50.00"),
          Memo,
          Some(depositId),
          BigDecimal("150.00"),
          OccurredAt.plusSeconds(60)
        )
        requireRight(operations.find(Customer)).balance shouldBe BigDecimal("150.00")
        requireRight(operations.adjustments(Customer)) shouldBe Vector(adjustment)
        val activities = requireRight(operations.activities(Customer))
        activities.map(_.operation) shouldBe
          Vector(BankingOperation.Deposit, BankingOperation.Adjustment)
        activities.last.originalTransactionId shouldBe Some(depositId)
        activities.head.status shouldBe AccountActivityStatus.Posted
      }

      test(s"$name: a debit adjustment cannot overdraw the account") {
        val operations = newOperations()
        fund(operations, Customer, "30.00")

        val result = operations.adjust(
          AdjustmentId(UUID.randomUUID()),
          Customer,
          AdjustmentDirection.Debit,
          money("30.01"),
          Memo,
          None,
          OccurredAt
        )

        result shouldBe Left(BankingError.InsufficientFunds(BigDecimal("30.00"), BigDecimal("30.01")))
        requireRight(operations.adjustments(Customer)) shouldBe empty
        requireRight(operations.find(Customer)).balance shouldBe BigDecimal("30.00")
      }

      test(s"$name: a linked transaction must exist and belong to the account") {
        val operations = newOperations()
        fund(operations, Customer, "10.00")
        val foreignDepositId = fund(operations, Other, "10.00")
        val missingId = UUID.randomUUID()

        def adjustLinkedTo(transactionId: UUID) =
          operations.adjust(
            AdjustmentId(UUID.randomUUID()),
            Customer,
            AdjustmentDirection.Credit,
            money("1.00"),
            Memo,
            Some(transactionId),
            OccurredAt
          )

        adjustLinkedTo(missingId) shouldBe Left(BankingError.AdjustedTransactionNotFound(missingId))
        adjustLinkedTo(foreignDepositId) shouldBe
          Left(BankingError.AdjustedTransactionAccountMismatch(foreignDepositId, Customer.value))
        requireRight(operations.find(Customer)).balance shouldBe BigDecimal("10.00")
      }

      test(s"$name: a reused adjustment ID is a duplicate and keeps the first memo") {
        val operations = newOperations()
        fund(operations, Customer, "10.00")
        val adjustmentId = AdjustmentId(UUID.randomUUID())
        def adjustWith(memo: AdjustmentMemo) =
          operations.adjust(
            adjustmentId,
            Customer,
            AdjustmentDirection.Credit,
            money("1.00"),
            memo,
            None,
            OccurredAt
          )
        requireRight(adjustWith(Memo))

        val replay = adjustWith(
          Memo.copy(reason = requireRight(AdjustmentReason.from(Some("Replay"))))
        )

        replay shouldBe Left(LedgerError.DuplicateTransaction(adjustmentId.value))
        requireRight(operations.adjustments(Customer)).map(_.memo) shouldBe Vector(Memo)
        requireRight(operations.find(Customer)).balance shouldBe BigDecimal("11.00")
      }

      test(s"$name: one transaction can be adjusted more than once") {
        val operations = newOperations()
        val depositId = fund(operations, Customer, "100.00")

        Vector(AdjustmentDirection.Credit -> "30.00", AdjustmentDirection.Debit -> "10.00").foreach {
          case (direction, amount) =>
            requireRight(
              operations.adjust(
                AdjustmentId(UUID.randomUUID()),
                Customer,
                direction,
                money(amount),
                Memo,
                Some(depositId),
                OccurredAt
              )
            )
        }

        requireRight(operations.adjustments(Customer)).map(_.balanceAfter) shouldBe
          Vector(BigDecimal("130.00"), BigDecimal("120.00"))
      }

      test(s"$name: an unknown account cannot be adjusted") {
        val operations = newOperations()

        operations.adjust(
          AdjustmentId(UUID.randomUUID()),
          Customer,
          AdjustmentDirection.Credit,
          money("1.00"),
          Memo,
          None,
          OccurredAt
        ) shouldBe Left(BankingError.AccountNotFound(Customer.value))
      }
  }

  private def inMemoryOperations(): LedgerBackedAccountOperations =
    new LedgerBackedAccountOperations(new InMemoryLedgerRepository(), new InMemoryAccountRepository())

  private def h2Operations(): LedgerBackedAccountOperations =
    val database = Database.open(Database.memoryUrl(s"ledger-adjustment-${UUID.randomUUID()}"))
    new LedgerBackedAccountOperations(
      new H2LedgerRepository(database),
      new H2AccountRepository(database)
    )

  private def fund(
      operations: LedgerBackedAccountOperations,
      accountId: AccountId,
      amount: String
  ): UUID =
    operations.open(accountId)
    val depositId = UUID.randomUUID()
    requireRight(operations.deposit(depositId, accountId, money(amount), OccurredAt))
    depositId

  private def money(amount: String): Money =
    requireRight(Money.positive(BigDecimal(amount)))

  private def requireRight[E, A](result: Either[E, A]): A =
    result.fold(error => fail(s"Expected Right, got $error"), identity)
