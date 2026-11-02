package com.example.banking.infrastructure

import java.time.{Clock, Instant, LocalDate, LocalTime, ZoneId, ZoneOffset, YearMonth}
import java.util.UUID

import com.example.banking.application.InterestRateService
import com.example.banking.domain.*
import com.example.banking.ledger.LedgerBackedAccountOperations
import com.example.banking.ledger.infrastructure.H2LedgerRepository
import com.example.infrastructure.{Database, DatabaseConfig}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class H2InterestPersistenceSpec extends AnyFunSuite with Matchers:
  private val Zone = ZoneId.of("Asia/Bangkok")

  test("H2 persists rate and processing state and records one interest credit per month") {
    val database = Database.open(
      Database.memoryUrl(s"interest-${UUID.randomUUID()}"),
      DatabaseConfig.DefaultUser,
      DatabaseConfig.DefaultPassword
    )
    try
      val accountRepository = new H2AccountRepository(database)
      val ledgerRepository = new H2LedgerRepository(database)
      val accountId = requireRight(AccountId.from("h2-interest-account"))
      accountRepository.open(accountId)
      val operations = new LedgerBackedAccountOperations(ledgerRepository, accountRepository)
      val aprilStart = LocalDate.parse("2027-04-01").atTime(LocalTime.MIDNIGHT).atZone(Zone).toInstant
      requireRight(
        operations.deposit(
          UUID.randomUUID(),
          accountId,
          requireRight(Money.positive(BigDecimal("10000.00"))),
          aprilStart
        )
      )

      val settings = new H2InterestSettingsRepository(database)
      settings.saveRate(
        InterestRateRecord(
          requireRight(AnnualInterestRate.fromPercent(BigDecimal("3.65"))),
          LocalDate.parse("2027-04-01"),
          Instant.parse("2027-03-20T00:00:00Z"),
          "admin"
        )
      )
      val reloadedSettings = new H2InterestSettingsRepository(database)
      reloadedSettings.rateFor(YearMonth.of(2027, 4)).percent shouldBe BigDecimal("3.65")
      reloadedSettings.nextPeriodToProcess(YearMonth.of(2027, 4)) shouldBe YearMonth.of(2027, 4)
      val service = new InterestRateService(
        ledgerRepository,
        accountRepository,
        reloadedSettings,
        Clock.fixed(Instant.parse("2027-05-02T00:00:00Z"), ZoneOffset.UTC),
        Zone
      )

      val first = requireRight(service.processCompletedPeriods())
      val retry = requireRight(service.processCompletedPeriods())

      first.map(_.month) shouldBe Vector(YearMonth.of(2027, 4))
      first.head.totalInterest shouldBe BigDecimal("30.00")
      retry shouldBe empty
      new H2InterestSettingsRepository(database).nextPeriodToProcess(YearMonth.of(2027, 4)) shouldBe
        YearMonth.of(2027, 5)
      ledgerRepository.balance(accountId) shouldBe BigDecimal("10030.00")
      ledgerRepository.transactions.count(_.operation == BankingOperation.InterestCredit) shouldBe 1
      ledgerRepository.activities(accountId).last.operation shouldBe BankingOperation.InterestCredit
    finally database.close()
  }

  private def requireRight[A](result: Either[?, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"Expected Right, got $error")
