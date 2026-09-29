package com.example.banking.application

import java.time.{Clock, Instant, ZoneOffset}
import java.util.UUID

import com.example.banking.infrastructure.InMemoryAccountRepository
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class DepositServiceSpec extends AnyFunSuite with Matchers:
  test("deposit returns a DepositCompleted domain event") {
    val occurredAt = Instant.parse("2026-09-29T10:15:30Z")
    val depositId = UUID.fromString("7a4459f6-a1f8-4a63-903b-fbf096a19b62")
    val depositService = new DepositService(
      new InMemoryAccountRepository(),
      Clock.fixed(occurredAt, ZoneOffset.UTC),
      () => depositId
    )

    val depositResult = depositService.deposit("account-123", BigDecimal("25.50"))

    depositResult match
      case Right(result) =>
        result.event.depositId.value shouldBe depositId
        result.event.accountId shouldBe result.account.id
        result.event.amount.amount shouldBe BigDecimal("25.50")
        result.event.resultingBalance shouldBe BigDecimal("25.50")
        result.event.occurredAt shouldBe occurredAt
      case Left(error) => fail(s"Expected a completed deposit, got ${error.code}")
  }
