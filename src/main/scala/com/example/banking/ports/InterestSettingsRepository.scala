package com.example.banking.ports

import java.time.YearMonth

import com.example.banking.domain.{AnnualInterestRate, InterestRateRecord}

trait InterestSettingsRepository:
  def rateRecords: Vector[InterestRateRecord]
  def saveRate(record: InterestRateRecord): InterestRateRecord
  def rateFor(period: YearMonth): AnnualInterestRate
  def nextPeriodToProcess(initialPeriod: YearMonth): YearMonth
  def markPeriodProcessed(period: YearMonth): Unit
