package com.example.banking.http

import com.example.banking.application.DepositService
import com.example.banking.infrastructure.InMemoryAccountRepository
import org.json4s.*
import org.json4s.jackson.JsonMethods.parse
import org.scalatra.test.scalatest.ScalatraFunSuite

final class BankingServletSpec extends ScalatraFunSuite:
  private implicit val jsonFormats: Formats = DefaultFormats
  private val depositService = new DepositService(new InMemoryAccountRepository())

  addServlet(new BankingServlet(depositService), "/accounts/*")

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

  private def postJson(path: String, jsonBody: String)(assertions: => Unit): Unit =
    post(path, jsonBody, Map("Content-Type" -> "application/json"))(assertions)
