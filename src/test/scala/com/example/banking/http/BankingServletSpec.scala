package com.example.banking.http

import java.time.Clock
import java.util.UUID

import com.example.banking.application.{AccountService, DepositService, WithdrawService}
import com.example.banking.infrastructure.{InMemoryAccountRepository, LocalMessageBus}
import com.example.banking.ledger.LedgerBackedAccountOperations
import com.example.banking.ledger.infrastructure.InMemoryLedgerRepository
import com.example.billpayment.application.BillPaymentService
import com.example.billpayment.infrastructure.{
  BillSeed,
  InMemoryBillerGateway,
  InMemoryBillPaymentInquiryRepository,
  InMemoryBillPaymentProcessRepository
}
import org.json4s.*
import org.json4s.jackson.JsonMethods.parse
import org.scalatra.test.scalatest.ScalatraFunSuite

final class BankingServletSpec extends ScalatraFunSuite:
  private implicit val jsonFormats: Formats = DefaultFormats
  private val accountOperations = new LedgerBackedAccountOperations(
    new InMemoryLedgerRepository(),
    new InMemoryAccountRepository()
  )
  private val accountService = new AccountService(accountOperations)
  private val messageBus = new LocalMessageBus()
  private val depositService = new DepositService(
    accountOperations,
    messageBus,
    Clock.systemUTC(),
    () => UUID.randomUUID()
  )
  private val withdrawService = new WithdrawService(
    accountOperations,
    messageBus,
    Clock.systemUTC(),
    () => UUID.randomUUID()
  )
  private val billPaymentService = new BillPaymentService(
    new InMemoryBillerGateway(
      Vector(BillSeed("demo-biller", "customer-001", "invoice-001", BigDecimal("100.00")))
    ),
    new InMemoryBillPaymentInquiryRepository(),
    new InMemoryBillPaymentProcessRepository(),
    messageBus,
    Clock.systemUTC(),
    () => UUID.randomUUID(),
    () => UUID.randomUUID()
  )

  addServlet(
    new BankingServlet(
      accountService,
      depositService,
      withdrawService,
      billPaymentService,
      accountOperations
    ),
    "/accounts/*"
  )

  test("PUT /accounts/:accountId creates an active zero-balance account idempotently") {
    putJson("/accounts/new-account", "{}") {
      status shouldBe 201
      (parse(body) \ "accountId").extract[String] shouldBe "new-account"
      (parse(body) \ "currency").extract[String] shouldBe "THB"
      (parse(body) \ "balance").extract[BigDecimal] shouldBe BigDecimal(0)
      (parse(body) \ "status").extract[String] shouldBe "active"
    }

    putJson("/accounts/new-account", "{}") {
      status shouldBe 200
    }

    get("/accounts/new-account/activities") {
      status shouldBe 200
      parse(body).extract[Vector[AccountActivityResponse]] shouldBe empty
    }
  }

  test("PUT /accounts/:accountId rejects an invalid account ID") {
    putJson("/accounts/not%20valid", "{}") {
      status shouldBe 400
      (parse(body) \ "error").extract[String] shouldBe "invalid_account_id"
    }
  }

  test("POST /accounts/:accountId/deposits creates an account balance") {
    createAccount("account-123")
    postJson("/accounts/account-123/deposits", """{"amount":25.50}""") {
      status shouldBe 200
      header("Content-Type") should startWith("application/json")
      (parse(body) \ "accountId").extract[String] shouldBe "account-123"
      (parse(body) \ "balance").extract[BigDecimal] shouldBe BigDecimal("25.50")
    }
  }

  test("POST /accounts/:accountId/deposits adds to the existing balance") {
    createAccount("existing-account")
    postJson("/accounts/existing-account/deposits", """{"amount":10.00}""") {
      status shouldBe 200
    }

    postJson("/accounts/existing-account/deposits", """{"amount":5.25}""") {
      status shouldBe 200
      (parse(body) \ "balance").extract[BigDecimal] shouldBe BigDecimal("15.25")
    }
  }

  test("POST /accounts/:accountId/deposits rejects non-positive amounts") {
    createAccount("account-123")
    postJson("/accounts/account-123/deposits", """{"amount":0}""") {
      status shouldBe 400
      (parse(body) \ "error").extract[String] shouldBe "invalid_deposit_amount"
    }
  }

  test("POST /accounts/:accountId/deposits rejects malformed JSON") {
    createAccount("account-123")
    postJson("/accounts/account-123/deposits", """{"amount":"many"}""") {
      status shouldBe 400
      (parse(body) \ "error").extract[String] shouldBe "invalid_request"
    }
  }

  test("POST /accounts/:accountId/withdrawals deducts from the account balance") {
    createAccount("withdrawal-account")
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
    createAccount("low-balance")
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

  test("GET /accounts/:accountId/balance returns the ledger-derived THB balance") {
    createAccount("balance-account")
    postJson("/accounts/balance-account/deposits", """{"amount":40.00}""") {
      status shouldBe 200
    }
    postJson("/accounts/balance-account/withdrawals", """{"amount":12.50}""") {
      status shouldBe 200
    }

    get("/accounts/balance-account/balance") {
      status shouldBe 200
      (parse(body) \ "accountId").extract[String] shouldBe "balance-account"
      (parse(body) \ "currency").extract[String] shouldBe "THB"
      (parse(body) \ "balance").extract[BigDecimal] shouldBe BigDecimal("27.50")
    }
  }

  test("GET /accounts/:accountId/activities returns customer-facing ledger activity") {
    createAccount("activity-account")
    postJson("/accounts/activity-account/deposits", """{"amount":20.00}""") {
      status shouldBe 200
    }
    postJson("/accounts/activity-account/withdrawals", """{"amount":5.00}""") {
      status shouldBe 200
    }

    get("/accounts/activity-account/activities") {
      status shouldBe 200
      val activities = parse(body).extract[Vector[AccountActivityResponse]]
      activities.map(_.operation) shouldBe Vector("deposit", "withdrawal")
      activities.map(_.effect) shouldBe Vector("increase", "decrease")
      activities.map(_.balanceAfter) shouldBe Vector(BigDecimal("20.00"), BigDecimal("15.00"))
      activities.foreach(_.currency shouldBe "THB")
    }
  }

  test("GET /accounts/:accountId/overview returns one consistent ledger snapshot") {
    createAccount("overview-account")
    postJson("/accounts/overview-account/deposits", """{"amount":20.00}""") {
      status shouldBe 200
    }
    postJson("/accounts/overview-account/withdrawals", """{"amount":5.00}""") {
      status shouldBe 200
    }

    get("/accounts/overview-account/overview") {
      status shouldBe 200
      val response = parse(body).extract[AccountOverviewResponse]
      response.accountId shouldBe "overview-account"
      response.currency shouldBe "THB"
      response.balance shouldBe BigDecimal("15.00")
      response.activities.map(_.operation) shouldBe Vector("deposit", "withdrawal")
      response.activities.last.balanceAfter shouldBe response.balance
    }
  }

  test("GET /accounts/:accountId/overview returns an empty zero-balance account") {
    createAccount("empty-overview-account")

    get("/accounts/empty-overview-account/overview") {
      status shouldBe 200
      val response = parse(body).extract[AccountOverviewResponse]
      response.balance shouldBe BigDecimal(0)
      response.activities shouldBe empty
    }
  }

  test("GET account ledger resources rejects an unknown account") {
    get("/accounts/unknown-ledger-account/balance") {
      status shouldBe 404
      (parse(body) \ "error").extract[String] shouldBe "account_not_found"
    }

    get("/accounts/unknown-ledger-account/activities") {
      status shouldBe 404
      (parse(body) \ "error").extract[String] shouldBe "account_not_found"
    }

    get("/accounts/unknown-ledger-account/overview") {
      status shouldBe 404
      (parse(body) \ "error").extract[String] shouldBe "account_not_found"
    }
  }

  private def postJson(path: String, jsonBody: String)(assertions: => Unit): Unit =
    post(path, jsonBody, Map("Content-Type" -> "application/json"))(assertions)

  private def putJson(path: String, jsonBody: String)(assertions: => Unit): Unit =
    put(path, jsonBody, Map("Content-Type" -> "application/json"))(assertions)

  private def createAccount(accountId: String): Unit =
    putJson(s"/accounts/$accountId", "{}") {
      status should (be(200) or be(201))
    }
