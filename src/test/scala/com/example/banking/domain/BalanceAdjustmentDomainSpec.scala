package com.example.banking.domain

import java.util.UUID

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class BalanceAdjustmentDomainSpec extends AnyFunSuite with Matchers:
  test("adjustment direction accepts credit and debit only") {
    AdjustmentDirection.from(Some("credit")) shouldBe Right(AdjustmentDirection.Credit)
    AdjustmentDirection.from(Some("debit")) shouldBe Right(AdjustmentDirection.Debit)
    AdjustmentDirection.from(Some("Credit")) shouldBe Left(BankingError.InvalidAdjustmentDirection)
    AdjustmentDirection.from(Some("sideways")) shouldBe Left(BankingError.InvalidAdjustmentDirection)
    AdjustmentDirection.from(None) shouldBe Left(BankingError.InvalidAdjustmentDirection)
  }

  test("adjustment amount must be positive with at most two decimals") {
    Money.positiveAdjustment(BigDecimal("0.01")).map(_.amount) shouldBe Right(BigDecimal("0.01"))
    Money.positiveAdjustment(BigDecimal(0)).left.map(_.code) shouldBe
      Left("invalid_adjustment_amount")
    Money.positiveAdjustment(BigDecimal("-1")).left.map(_.code) shouldBe
      Left("invalid_adjustment_amount")
    Money.positiveAdjustment(BigDecimal("1.001")).left.map(_.code) shouldBe
      Left("invalid_adjustment_amount")
  }

  test("adjustment reason is trimmed, required, and at most 500 characters") {
    AdjustmentReason.from(Some("  Deposit keyed wrong  ")).map(_.value) shouldBe
      Right("Deposit keyed wrong")
    AdjustmentReason.from(Some("x" * 500)).map(_.value.length) shouldBe Right(500)
    AdjustmentReason.from(Some("x" * 501)).left.map(_.code) shouldBe
      Left("invalid_adjustment_reason")
    AdjustmentReason.from(Some("   ")).left.map(_.code) shouldBe Left("invalid_adjustment_reason")
    AdjustmentReason.from(None).left.map(_.code) shouldBe Left("invalid_adjustment_reason")
  }

  test("admin actor is trimmed and required") {
    AdminActor.from(Some(" ops-alice ")).map(_.value) shouldBe Right("ops-alice")
    AdminActor.from(Some("x" * 129)) shouldBe Left(BankingError.MissingAdminActor)
    AdminActor.from(Some("")) shouldBe Left(BankingError.MissingAdminActor)
    AdminActor.from(None) shouldBe Left(BankingError.MissingAdminActor)
  }

  test("adjustment id comes from a UUID idempotency key") {
    val key = UUID.randomUUID()
    AdjustmentId.fromIdempotencyKey(Some(key.toString)) shouldBe Right(AdjustmentId(key))
    AdjustmentId.fromIdempotencyKey(Some("not-a-uuid")) shouldBe
      Left(BankingError.InvalidIdempotencyKey)
    AdjustmentId.fromIdempotencyKey(None) shouldBe Left(BankingError.InvalidIdempotencyKey)
  }
