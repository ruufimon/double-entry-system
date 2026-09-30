package com.example.banking.http

import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

import com.example.banking.application.{BillPaymentService, DepositService, WithdrawService}
import com.example.banking.infrastructure.{
  BillSeed,
  InMemoryAccountRepository,
  InMemoryBillerGateway,
  InMemoryBillPaymentInquiryRepository,
  LocalMessageBus
}
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
  private val billPaymentService = new BillPaymentService(
    accountRepository,
    new InMemoryBillerGateway(
      Vector(BillSeed("demo-biller", "customer-001", "invoice-001", BigDecimal("100.00")))
    ),
    new InMemoryBillPaymentInquiryRepository(),
    messageBus,
    Clock.systemUTC(),
    () => UUID.randomUUID(),
    () => UUID.randomUUID()
  )

  addServlet(
    new BankingServlet(depositService, withdrawService, billPaymentService),
    "/accounts/*"
  )

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

  test("bill payment inquiry and confirmation complete the quoted debt") {
    val inquiryId = new AtomicReference[String]()
    postJson("/accounts/bill-account/deposits", """{"amount":150.00}""") {
      status shouldBe 200
    }

    postJson(
      "/accounts/bill-account/bill-payments/inquiries",
      """{"billerCode":"demo-biller","referenceCode1":"customer-001","referenceCode2":"invoice-001"}"""
    ) {
      status shouldBe 200
      (parse(body) \ "currentDebt").extract[BigDecimal] shouldBe BigDecimal("100.00")
      inquiryId.set((parse(body) \ "inquiryId").extract[String])
    }

    postJson(
      s"/accounts/bill-account/bill-payments/${inquiryId.get()}/confirm",
      "{}"
    ) {
      status shouldBe 200
      (parse(body) \ "accountId").extract[String] shouldBe "bill-account"
      (parse(body) \ "billerCode").extract[String] shouldBe "demo-biller"
      (parse(body) \ "amount").extract[BigDecimal] shouldBe BigDecimal("100.00")
      (parse(body) \ "resultingBalance").extract[BigDecimal] shouldBe BigDecimal("50.00")
      (parse(body) \ "billerReceiptCode").extract[String] should not be empty
    }
  }

  test("bill payment inquiry rejects unknown bill references") {
    postJson("/accounts/reference-account/deposits", """{"amount":100.00}""") {
      status shouldBe 200
    }

    postJson(
      "/accounts/reference-account/bill-payments/inquiries",
      """{"billerCode":"demo-biller","referenceCode1":"unknown","referenceCode2":"unknown"}"""
    ) {
      status shouldBe 404
      (parse(body) \ "error").extract[String] shouldBe "bill_not_found"
    }
  }

  private def postJson(path: String, jsonBody: String)(assertions: => Unit): Unit =
    post(path, jsonBody, Map("Content-Type" -> "application/json"))(assertions)
