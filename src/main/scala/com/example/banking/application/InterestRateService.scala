package com.example.banking.application

import java.nio.charset.StandardCharsets
import java.time.{Clock, Instant, LocalDate, LocalTime, YearMonth, ZoneId}
import java.util.UUID

import cats.data.NonEmptyVector
import com.example.banking.domain.*
import com.example.banking.ledger.*
import com.example.banking.ports.{AccountRepository, InterestSettingsRepository}
import com.example.domain.DomainError

final class InterestRateService(
    ledgerRepository: LedgerRepository,
    accountRepository: AccountRepository,
    settingsRepository: InterestSettingsRepository,
    clock: Clock,
    zoneId: ZoneId
):
  def overview: InterestRateOverview =
    val today = clock.instant().atZone(zoneId).toLocalDate
    val currentMonthStart = YearMonth.from(today).atDay(1)
    val records = settingsRepository.rateRecords
    val indexed = records.zipWithIndex
    val current = indexed
      .filter { case (record, _) => !record.effectiveFrom.isAfter(currentMonthStart) }
      .maxByOption { case (record, index) => (record.effectiveFrom, index) }
      .getOrElse(records.head -> 0)
    val scheduledDate = indexed
      .map(_._1.effectiveFrom)
      .filter(_.isAfter(currentMonthStart))
      .minOption
    val scheduled = scheduledDate.flatMap { date =>
      indexed.filter(_._1.effectiveFrom == date).maxByOption(_._2)
    }

    InterestRateOverview(
      currentRate = current._1.rate,
      currentEffectiveFrom = current._1.effectiveFrom,
      nextEffectiveFrom = YearMonth.from(today).plusMonths(1).atDay(1),
      scheduledRate = scheduled.map(_._1.rate),
      scheduledEffectiveFrom = scheduled.map(_._1.effectiveFrom),
      history = indexed.map { case (record, index) =>
        val status =
          if index == current._2 then InterestRateStatus.Current
          else if scheduled.exists(_._2 == index) then InterestRateStatus.Scheduled
          else InterestRateStatus.Superseded
        InterestRateHistoryItem(
          record.rate,
          record.effectiveFrom,
          record.createdAt,
          record.updatedBy,
          status
        )
      }.reverse
    )

  def setRate(percent: BigDecimal, updatedBy: String): Either[DomainError, InterestRateOverview] =
    AnnualInterestRate.fromPercent(percent).map { rate =>
      val now = clock.instant()
      val effectiveFrom = YearMonth.from(now.atZone(zoneId)).plusMonths(1).atDay(1)
      settingsRepository.saveRate(
        InterestRateRecord(rate, effectiveFrom, now, updatedBy)
      )
      overview
    }

  def processCompletedPeriods(): Either[DomainError, Vector[InterestPeriod]] =
    val currentMonth = YearMonth.from(clock.instant().atZone(zoneId))
    val lastCompletedMonth = currentMonth.minusMonths(1)
    var nextPeriod = settingsRepository.nextPeriodToProcess(currentMonth)
    val results = Vector.newBuilder[InterestPeriod]
    var failure: Option[DomainError] = None
    while !nextPeriod.isAfter(lastCompletedMonth) && failure.isEmpty do
      processPeriod(nextPeriod) match
        case Left(error) => failure = Some(error)
        case Right(result) =>
          settingsRepository.markPeriodProcessed(nextPeriod)
          results += result
          nextPeriod = nextPeriod.plusMonths(1)
    failure match
      case Some(error) => Left(error)
      case None        => Right(results.result())

  /** Processes a completed month; useful for deterministic operational and acceptance tests. */
  def processPeriod(period: YearMonth): Either[DomainError, InterestPeriod] =
    val rate = settingsRepository.rateFor(period)
    val start = period.atDay(1)
    val end = period.atEndOfMonth
    val daysInYear = if period.isLeapYear then BigDecimal(366) else BigDecimal(365)
    accountRepository.all.foldLeft(
      Right(InterestPeriod(period, 0, BigDecimal(0))): Either[DomainError, InterestPeriod]
    ) { (accumulated, account) =>
      accumulated.flatMap { total =>
        val dayBalances = ledgerRepository.dailyClosingBalances(account.id, start, end, zoneId)
        val positiveBalanceDays = dayBalances.map(_._2.max(BigDecimal(0))).sum
        val amount = (positiveBalanceDays * BigDecimal(rate.basisPoints) /
          BigDecimal(10000) / daysInYear).setScale(2, BigDecimal.RoundingMode.HALF_UP)
        if amount <= 0 then Right(total)
        else
          val occurredAt = end.atTime(LocalTime.of(23, 59, 59)).atZone(zoneId).toInstant
          createTransaction(account.id, amount, period, occurredAt).flatMap { transaction =>
            ledgerRepository.postInterestCredit(transaction, account.id, period).map { posted =>
              if posted then
                total.copy(
                  creditedAccounts = total.creditedAccounts + 1,
                  totalInterest = total.totalInterest + amount
                )
              else total
            }
          }
      }
    }

  private def createTransaction(
      accountId: AccountId,
      amount: BigDecimal,
      period: YearMonth,
      occurredAt: Instant
  ): Either[DomainError, LedgerTransaction] =
    val transactionId = UUID.nameUUIDFromBytes(
      s"monthly-interest:${accountId.value}:${period.toString}".getBytes(StandardCharsets.UTF_8)
    )
    val money = Money.of(amount)
    LedgerTransaction.create(
      transactionId,
      BankingOperation.InterestCredit,
      NonEmptyVector.of(
        LedgerEntry(LedgerAccount.InterestExpense, LedgerDirection.Debit, money, Currency.THB),
        LedgerEntry(LedgerAccount.Customer(accountId), LedgerDirection.Credit, money, Currency.THB)
      ),
      occurredAt
    )
