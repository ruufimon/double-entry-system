package com.example.banking.domain

import java.time.{Instant, LocalDate, YearMonth}

import com.example.domain.DomainError

final case class AnnualInterestRate private (basisPoints: Long) derives CanEqual:
  def percent: BigDecimal = BigDecimal(basisPoints) / BigDecimal(100)

object AnnualInterestRate:
  val Zero: AnnualInterestRate = AnnualInterestRate(0L)

  def fromPercent(value: BigDecimal): Either[InterestError, AnnualInterestRate] =
    if value < 0 then Left(InterestError.InvalidRate("Rate cannot be negative"))
    else if value.scale > 2 then
      Left(InterestError.InvalidRate("Rate must have at most two decimal places"))
    else
      val basisPoints = value * 100
      if basisPoints.isWhole && basisPoints.isValidLong then
        Right(AnnualInterestRate(basisPoints.toLong))
      else Left(InterestError.InvalidRate("Rate is outside the supported range"))

  /** Constructs from a validated stored basis-point value. */
  def unsafe(basisPoints: Long): AnnualInterestRate = AnnualInterestRate(basisPoints)

final case class InterestRateRecord(
    rate: AnnualInterestRate,
    effectiveFrom: LocalDate,
    createdAt: Instant,
    updatedBy: String
) derives CanEqual

enum InterestRateStatus derives CanEqual:
  case Current, Scheduled, Superseded

final case class InterestRateHistoryItem(
    rate: AnnualInterestRate,
    effectiveFrom: LocalDate,
    createdAt: Instant,
    updatedBy: String,
    status: InterestRateStatus
) derives CanEqual

final case class InterestRateOverview(
    currentRate: AnnualInterestRate,
    currentEffectiveFrom: LocalDate,
    nextEffectiveFrom: LocalDate,
    scheduledRate: Option[AnnualInterestRate],
    scheduledEffectiveFrom: Option[LocalDate],
    history: Vector[InterestRateHistoryItem]
) derives CanEqual

final case class InterestPeriod(
    month: YearMonth,
    creditedAccounts: Int,
    totalInterest: BigDecimal
) derives CanEqual

enum InterestError(val code: String, val message: String) extends DomainError derives CanEqual:
  case InvalidRate(details: String)
      extends InterestError("invalid_interest_rate", details)
  case InvalidPeriod(details: String)
      extends InterestError("invalid_interest_period", details)
