package com.example.banking.infrastructure

import java.time.{Instant, LocalDate, YearMonth}

import com.example.banking.domain.{AnnualInterestRate, InterestRateRecord}
import com.example.banking.ports.InterestSettingsRepository

final class InMemoryInterestSettingsRepository extends InterestSettingsRepository:
  private var records = Vector(
    InterestRateRecord(
      AnnualInterestRate.Zero,
      LocalDate.of(1, 1, 1),
      Instant.EPOCH,
      "System default"
    )
  )
  private var nextPeriod: Option[YearMonth] = None

  override def rateRecords: Vector[InterestRateRecord] = synchronized(records)

  override def saveRate(record: InterestRateRecord): InterestRateRecord = synchronized {
    records :+= record
    record
  }

  override def rateFor(period: YearMonth): AnnualInterestRate = synchronized {
    records
      .filter(record => !record.effectiveFrom.isAfter(period.atDay(1)))
      .maxBy(record => (record.effectiveFrom, record.createdAt))
      .rate
  }

  override def nextPeriodToProcess(initialPeriod: YearMonth): YearMonth = synchronized {
    nextPeriod.getOrElse {
      nextPeriod = Some(initialPeriod)
      initialPeriod
    }
  }

  override def markPeriodProcessed(period: YearMonth): Unit = synchronized {
    nextPeriod = Some(period.plusMonths(1))
  }
