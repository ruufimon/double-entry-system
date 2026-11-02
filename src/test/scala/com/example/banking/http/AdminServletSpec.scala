package com.example.banking.http

import java.time.{Clock, Instant, ZoneOffset}
import java.util.UUID

import com.example.banking.application.AdjustmentService
import com.example.banking.domain.{AccountId, Money}
import com.example.banking.infrastructure.{InMemoryAccountRepository, LocalMessageBus}
import com.example.banking.ledger.LedgerBackedAccountOperations
import com.example.banking.ledger.infrastructure.InMemoryLedgerRepository
import org.json4s.*
import org.json4s.jackson.JsonMethods.parse
import org.scalatra.test.scalatest.ScalatraFunSuite

final class AdminServletSpec extends ScalatraFunSuite:
  private implicit val jsonFormats: Formats = DefaultFormats
  private val operations = new LedgerBackedAccountOperations(
    new InMemoryLedgerRepository(),
    new InMemoryAccountRepository()
  )
  private val occurredAt = Instant.parse("2026-10-07T04:00:00Z")
  private val adminToken = "servlet-admin-token"
  private val adjustmentService = new AdjustmentService(
    operations,
    new LocalMessageBus(),
    Clock.fixed(occurredAt, ZoneOffset.UTC)
  )

  addServlet(new AdminServlet(operations, adjustmentService, None, Some(adminToken), None), "/admin/*")

  test("GET /admin/accounts returns an empty list") {
    get("/admin/accounts") {
      status shouldBe 200
      parse(body).extract[Vector[AdminAccountSummaryResponse]] shouldBe empty
    }
  }

  test("GET /admin/accounts returns sorted ledger-derived summaries") {
    open("zeta-account")
    open("alpha-account")
    val zetaId = accountId("zeta-account")
    requireRight(
      operations.deposit(
        UUID.randomUUID(),
        zetaId,
        money("20.00"),
        occurredAt
      )
    )

    get("/admin/accounts") {
      status shouldBe 200
      val accounts = parse(body).extract[Vector[AdminAccountSummaryResponse]]
      accounts.map(_.accountId) shouldBe Vector("alpha-account", "zeta-account")
      accounts.head.balance shouldBe BigDecimal(0)
      accounts.head.activityCount shouldBe 0
      accounts.head.lastActivityAt shouldBe None
      accounts.last.balance shouldBe BigDecimal("20.00")
      accounts.last.activityCount shouldBe 1
      accounts.last.lastActivityAt shouldBe Some(occurredAt.toString)
      accounts.foreach(_.status shouldBe "active")
    }
  }

  test("GET /admin/accounts/:accountId returns complete chronological activity") {
    val id = accountId("detail-account")
    requireRight(operations.open(id))
    val depositId = UUID.randomUUID()
    val withdrawalId = UUID.randomUUID()
    requireRight(operations.deposit(depositId, id, money("30.00"), occurredAt))
    requireRight(
      operations.withdraw(
        withdrawalId,
        id,
        money("5.00"),
        occurredAt.plusSeconds(60)
      )
    )

    get("/admin/accounts/detail-account") {
      status shouldBe 200
      val account = parse(body).extract[AdminAccountDetailResponse]
      account.accountId shouldBe "detail-account"
      account.currency shouldBe "THB"
      account.status shouldBe "active"
      account.balance shouldBe BigDecimal("25.00")
      account.activityCount shouldBe 2
      account.lastActivityAt shouldBe Some(occurredAt.plusSeconds(60).toString)
      account.activities.map(_.transactionId) shouldBe Vector(
        depositId.toString,
        withdrawalId.toString
      )
      account.activities.map(_.operation) shouldBe Vector("deposit", "withdrawal")
      account.activities.map(_.balanceAfter) shouldBe Vector(
        BigDecimal("30.00"),
        BigDecimal("25.00")
      )
    }
  }

  test("lastActivityAt is the newest activity even when an older one is posted last") {
    val id = accountId("backdated-account")
    requireRight(operations.open(id))
    val newest = occurredAt.plusSeconds(60)
    requireRight(operations.deposit(UUID.randomUUID(), id, money("20.00"), newest))
    requireRight(operations.deposit(UUID.randomUUID(), id, money("10.00"), occurredAt))

    get("/admin/accounts/backdated-account") {
      status shouldBe 200
      val account = parse(body).extract[AdminAccountDetailResponse]
      account.lastActivityAt shouldBe Some(newest.toString)
      account.activities.map(_.occurredAt) shouldBe Vector(occurredAt.toString, newest.toString)
      account.balance shouldBe BigDecimal("30.00")
    }

    get("/admin/accounts") {
      status shouldBe 200
      val summary = parse(body)
        .extract[Vector[AdminAccountSummaryResponse]]
        .find(_.accountId == "backdated-account")
      summary.flatMap(_.lastActivityAt) shouldBe Some(newest.toString)
    }
  }

  test("GET /admin/accounts/:accountId rejects invalid and missing accounts") {
    get("/admin/accounts/not%20valid") {
      status shouldBe 400
      (parse(body) \ "error").extract[String] shouldBe "invalid_account_id"
    }

    get("/admin/accounts/missing-account") {
      status shouldBe 404
      (parse(body) \ "error").extract[String] shouldBe "account_not_found"
    }
  }

  test("POST /admin/accounts/:accountId/adjustments returns the created adjustment") {
    val id = accountId("servlet-adjusted")
    requireRight(operations.open(id))
    val depositId = UUID.randomUUID()
    requireRight(operations.deposit(depositId, id, money("10.00"), occurredAt))
    val key = UUID.randomUUID().toString

    post(
      "/admin/accounts/servlet-adjusted/adjustments",
      s"""{"direction":"debit","amount":2.50,"reason":"Fee refund reversed","adjustsTransactionId":"$depositId"}""",
      adminHeaders(idempotencyKey = key)
    ) {
      status shouldBe 201
      parse(body).extract[AdminAdjustmentResponse] shouldBe AdminAdjustmentResponse(
        adjustmentId = key,
        accountId = "servlet-adjusted",
        direction = "debit",
        amount = BigDecimal("2.50"),
        currency = "THB",
        reason = "Fee refund reversed",
        actor = "ops-alice",
        adjustsTransactionId = Some(depositId.toString),
        balanceAfter = BigDecimal("7.50"),
        occurredAt = occurredAt.toString
      )
    }

    get("/admin/accounts/servlet-adjusted/adjustments", headers = adminHeaders()) {
      status shouldBe 200
      parse(body).extract[Vector[AdminAdjustmentResponse]].map(_.adjustmentId) shouldBe
        Vector(key)
    }

    get("/admin/accounts/servlet-adjusted") {
      status shouldBe 200
      parse(body).extract[AdminAccountDetailResponse].activities.last.operation shouldBe
        "adjustment"
    }
  }

  test("adjustment routes require the configured bearer token") {
    open("servlet-guarded")
    val requestBody = """{"direction":"credit","amount":1.00,"reason":"Fix"}"""

    Vector(
      Map.empty[String, String],
      Map("Authorization" -> adminToken),
      Map("Authorization" -> s"Bearer ${adminToken}x"),
      Map("Authorization" -> "Bearer ")
    ).foreach { authorization =>
      post(
        "/admin/accounts/servlet-guarded/adjustments",
        requestBody,
        adminHeaders() - "Authorization" ++ authorization
      ) {
        status shouldBe 401
        (parse(body) \ "error").extract[String] shouldBe "unauthorized"
      }
    }
    get("/admin/accounts/servlet-guarded/adjustments") {
      status shouldBe 401
    }
    requireRight(operations.find(accountId("servlet-guarded"))).balance shouldBe BigDecimal(0)
  }

  test("adjustment errors map to HTTP status codes") {
    open("servlet-errors")
    def statusFor(path: String, body: String, headers: Map[String, String] = adminHeaders()) =
      post(path, body, headers)(status)

    statusFor("/admin/accounts/servlet-errors/adjustments", "{]") shouldBe 400
    statusFor(
      "/admin/accounts/servlet-errors/adjustments",
      """{"direction":"debit","amount":1.00,"reason":"Fix"}"""
    ) shouldBe 409
    statusFor(
      "/admin/accounts/servlet-missing/adjustments",
      """{"direction":"credit","amount":1.00,"reason":"Fix"}"""
    ) shouldBe 404
    statusFor(
      "/admin/accounts/servlet-errors/adjustments",
      s"""{"direction":"credit","amount":1.00,"reason":"Fix","adjustsTransactionId":"${UUID.randomUUID()}"}"""
    ) shouldBe 404
  }

  private def adminHeaders(
      idempotencyKey: String = UUID.randomUUID().toString
  ): Map[String, String] =
    Map(
      "Authorization" -> s"Bearer $adminToken",
      "X-Admin-Actor" -> "ops-alice",
      "Idempotency-Key" -> idempotencyKey,
      "Content-Type" -> "application/json"
    )

  private def open(rawAccountId: String): Unit =
    requireRight(operations.open(accountId(rawAccountId)))

  private def accountId(rawAccountId: String): AccountId =
    requireRight(AccountId.from(rawAccountId))

  private def money(amount: String): Money =
    requireRight(Money.positive(BigDecimal(amount)))

  private def requireRight[A](result: Either[?, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"Expected Right, got $error")
