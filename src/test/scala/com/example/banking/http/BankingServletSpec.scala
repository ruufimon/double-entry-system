package com.example.banking.http

import java.time.Clock
import java.util.UUID

import com.example.banking.application.{DepositService, WithdrawService}
import com.example.banking.infrastructure.{InMemoryAccountRepository, LocalMessageBus}
import org.json4s.*
import org.json4s.jackson.JsonMethods.parse
import org.scalatra.test.scalatest.ScalatraFunSuite

final class BankingServletSpec extends ScalatraFunSuite:
  private implicit val jsonFormats: Formats = DefaultFormats
  private val accountRepository = new InMemoryAccountRepository()
  private val messageBus = new LocalMessageBus()
  private val depositService = new DepositService(
    accountRepository,
    messageBus,
    Clock.systemUTC(),
    () => UUID.randomUUID()
  )
  private val withdrawService = new WithdrawService(
    accountRepository,
    messageBus,
    Clock.systemUTC(),
    () => UUID.randomUUID()
  )

  addServlet(new BankingServlet(depositService, withdrawService), "/accounts/*")

  test("POST /accounts/:accountId/deposits creates an account balance") {
    postJson("/accounts/account-123/deposits", """{"amount":25.50}""") {
      status shouldBe 200
      header("Content-Type") should startWith("application/json")
      (parse(body) \ "accountId").extract[String] shouldBe "account-123"
      (parse(body) \ "balance").extract[BigDecimal] shouldBe BigDecimal("25.50")
    }
  }

  test("POST /accounts/:accountId/deposits adds to the existing balance") {
    postJson("/accounts/existing-account/deposits", """{"amount":10.00}""") {
      status shouldBe 200
    }

    postJson("/accounts/existing-account/deposits", """{"amount":5.25}""") {
      status shouldBe 200
      (parse(body) \ "balance").extract[BigDecimal] shouldBe BigDecimal("15.25")
    }
  }

  test("POST /accounts/:accountId/deposits rejects non-positive amounts") {
    postJson("/accounts/account-123/deposits", """{"amount":0}""") {
      status shouldBe 400
      (parse(body) \ "error").extract[String] shouldBe "invalid_deposit_amount"
    }
  }

  test("POST /accounts/:accountId/deposits rejects malformed JSON") {
    postJson("/accounts/account-123/deposits", """{"amount":"many"}""") {
      status shouldBe 400
      (parse(body) \ "error").extract[String] shouldBe "invalid_request"
    }
  }

  test("POST /accounts/:accountId/withdrawals deducts from the account balance") {
    postJson("/accounts/withdrawal-account/deposits", """{"amount":50.00}""") {
      status shouldBe 200
    }

    postJson("/accounts/withdrawal-account/withdrawals", """{"amount":12.50}""") {
      status shouldBe 200
      (parse(body) \ "accountId").extract[String] shouldBe "withdrawal-account"
      (parse(body) \ "balance").extract[BigDecimal] shouldBe BigDecimal("37.50")
    }
  }

  test("POST /accounts/:accountId/withdrawals rejects insufficient funds") {
    postJson("/accounts/low-balance/deposits", """{"amount":10.00}""") {
      status shouldBe 200
    }

    postJson("/accounts/low-balance/withdrawals", """{"amount":10.01}""") {
      status shouldBe 409
      (parse(body) \ "error").extract[String] shouldBe "insufficient_funds"
    }
  }

  test("POST /accounts/:accountId/withdrawals rejects an unknown account") {
    postJson("/accounts/missing-account/withdrawals", """{"amount":5.00}""") {
      status shouldBe 404
      (parse(body) \ "error").extract[String] shouldBe "account_not_found"
    }
  }

  private def postJson(path: String, jsonBody: String)(assertions: => Unit): Unit =
    post(path, jsonBody, Map("Content-Type" -> "application/json"))(assertions)
