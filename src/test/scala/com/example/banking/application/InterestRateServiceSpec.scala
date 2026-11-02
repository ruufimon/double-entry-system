package com.example.banking.application

import java.time.{Clock, Instant, LocalDate, LocalTime, ZoneId, ZoneOffset, YearMonth}
import java.util.UUID

import com.example.banking.domain.*
import com.example.banking.infrastructure.{InMemoryAccountRepository, InMemoryInterestSettingsRepository}
import com.example.banking.ledger.LedgerBackedAccountOperations
import com.example.banking.ledger.infrastructure.InMemoryLedgerRepository
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class InterestRateServiceSpec extends AnyFunSuite with Matchers:
  private val Zone = ZoneId.of("Asia/Bangkok")
  private val Now = Instant.parse("2026-10-28T05:00:00Z")

  test("uses zero as the initial rate and schedules a changed rate for next month") {
    val (service, _) = createService()
    service.overview.currentRate.percent shouldBe BigDecimal("0.00")

    val updated = requireRight(service.setRate(BigDecimal("3.65"), "admin"))

    updated.currentRate.percent shouldBe BigDecimal("0.00")
    updated.scheduledRate.map(_.percent) shouldBe Some(BigDecimal("3.65"))
    updated.scheduledEffectiveFrom shouldBe Some(LocalDate.parse("2026-11-01"))
    updated.history.map(_.status) shouldBe Vector(
      InterestRateStatus.Scheduled,
      InterestRateStatus.Current
    )
  }

  test("the latest rate saved for a coming month is scheduled") {
    val (service, _) = createService()
    requireRight(service.setRate(BigDecimal("3.65"), "admin"))
    val updated = requireRight(service.setRate(BigDecimal("2.50"), "admin"))

    updated.scheduledRate.map(_.percent) shouldBe Some(BigDecimal("2.50"))
    updated.history.map(item => item.rate.percent -> item.status) shouldBe Vector(
      BigDecimal("2.50") -> InterestRateStatus.Scheduled,
      BigDecimal("3.65") -> InterestRateStatus.Superseded,
      BigDecimal("0.00") -> InterestRateStatus.Current
    )
  }

  test("rejects negative rates and rates with more than two decimal places") {
    val (service, _) = createService()
    service.setRate(BigDecimal("-0.01"), "admin").isLeft shouldBe true
    service.setRate(BigDecimal("3.651"), "admin").isLeft shouldBe true
  }

  test("credits monthly interest from daily closing balances") {
    val (service, operations, ledger, accountId) = createAprilService()
    deposit(operations, accountId, "10000.00", atLocal("2027-04-01", "00:00:00"))
    deposit(operations, accountId, "10000.00", atLocal("2027-04-16", "12:00:00"))

    val result = requireRight(service.processPeriod(YearMonth.of(2027, 4)))

    result.creditedAccounts shouldBe 1
    result.totalInterest shouldBe BigDecimal("45.00")
    ledger.balance(accountId) shouldBe BigDecimal("20045.00")
    ledger.activities(accountId).last.operation shouldBe BankingOperation.InterestCredit
  }

  test("uses 366 days in a leap year and rounds the monthly credit to satang") {
    val (service, operations, ledger, accountId) = createAprilService()
    deposit(operations, accountId, "10000.00", atLocal("2028-02-01", "00:00:00"))

    val result = requireRight(service.processPeriod(YearMonth.of(2028, 2)))

    result.totalInterest shouldBe BigDecimal("28.92")
    ledger.balance(accountId) shouldBe BigDecimal("10028.92")
  }

  test("does not credit non-positive balances and does not duplicate a period on retry") {
    val (service, operations, ledger, accountId) = createAprilService()
    deposit(operations, accountId, "10000.00", atLocal("2027-04-01", "00:00:00"))

    val first = requireRight(service.processPeriod(YearMonth.of(2027, 4)))
    val afterFirst = ledger.balance(accountId)
    val second = requireRight(service.processPeriod(YearMonth.of(2027, 4)))

    first.creditedAccounts shouldBe 1
    second.creditedAccounts shouldBe 0
    ledger.balance(accountId) shouldBe afterFirst
    ledger.activities(accountId).count(_.operation == BankingOperation.InterestCredit) shouldBe 1
  }

  test("does not accrue interest when same-day transactions leave a zero closing balance") {
    val (service, operations, ledger, accountId) = createAprilService()
    val sameTime = atLocal("2027-04-01", "12:00:00")
    deposit(operations, accountId, "10000.00", sameTime)
    requireRight(
      operations.withdraw(
        UUID.randomUUID(),
        accountId,
        requireRight(Money.positive(BigDecimal("10000.00"))),
        sameTime
      )
    )

    val result = requireRight(service.processPeriod(YearMonth.of(2027, 4)))

    result.creditedAccounts shouldBe 0
    result.totalInterest shouldBe BigDecimal(0)
    ledger.balance(accountId) shouldBe BigDecimal(0)
  }

  test("catches up completed months after a restart") {
    val accountRepository = new InMemoryAccountRepository()
    val accountId = requireRight(AccountId.from("interest-catch-up"))
    accountRepository.open(accountId)
    val ledger = new InMemoryLedgerRepository()
    val operations = new LedgerBackedAccountOperations(ledger, accountRepository)
    deposit(operations, accountId, "10000.00", atLocal("2027-04-01", "00:00:00"))
    val settings = new InMemoryInterestSettingsRepository()
    settings.saveRate(
      InterestRateRecord(
        requireRight(AnnualInterestRate.fromPercent(BigDecimal("3.65"))),
        LocalDate.parse("2027-04-01"),
        Instant.parse("2027-03-20T00:00:00Z"),
        "admin"
      )
    )
    settings.nextPeriodToProcess(YearMonth.of(2027, 4))
    val mayClock = Clock.fixed(Instant.parse("2027-05-02T00:00:00Z"), ZoneOffset.UTC)
    val service = new InterestRateService(ledger, accountRepository, settings, mayClock, Zone)

    val processed = requireRight(service.processCompletedPeriods())
    val retried = requireRight(service.processCompletedPeriods())

    processed.map(_.month) shouldBe Vector(YearMonth.of(2027, 4))
    processed.head.totalInterest shouldBe BigDecimal("30.00")
    retried shouldBe empty
    ledger.balance(accountId) shouldBe BigDecimal("10030.00")
  }

  private def createService(): (InterestRateService, InMemoryInterestSettingsRepository) =
    val accounts = new InMemoryAccountRepository()
    val ledger = new InMemoryLedgerRepository()
    val settings = new InMemoryInterestSettingsRepository()
    val clock = Clock.fixed(Now, ZoneOffset.UTC)
    (new InterestRateService(ledger, accounts, settings, clock, Zone), settings)

  private def createAprilService(): (
      InterestRateService,
      LedgerBackedAccountOperations,
      InMemoryLedgerRepository,
      AccountId
  ) =
    val accountRepository = new InMemoryAccountRepository()
    val accountId = requireRight(AccountId.from("interest-test-account"))
    accountRepository.open(accountId)
    val ledger = new InMemoryLedgerRepository()
    val operations = new LedgerBackedAccountOperations(ledger, accountRepository)
    val settings = new InMemoryInterestSettingsRepository()
    settings.saveRate(
      InterestRateRecord(
        requireRight(AnnualInterestRate.fromPercent(BigDecimal("3.65"))),
        LocalDate.parse("2027-04-01"),
        Instant.parse("2027-03-20T00:00:00Z"),
        "admin"
      )
    )
    val clock = Clock.fixed(Instant.parse("2027-05-01T00:00:00Z"), ZoneOffset.UTC)
    (new InterestRateService(ledger, accountRepository, settings, clock, Zone), operations, ledger, accountId)

  private def deposit(
      operations: LedgerBackedAccountOperations,
      accountId: AccountId,
      amount: String,
      occurredAt: Instant
  ): Unit =
    val money = requireRight(Money.positive(BigDecimal(amount)))
    requireRight(operations.deposit(UUID.randomUUID(), accountId, money, occurredAt))

  private def atLocal(date: String, time: String): Instant =
    LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(Zone).toInstant

  private def requireRight[A](result: Either[?, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"Expected Right, got $error")
