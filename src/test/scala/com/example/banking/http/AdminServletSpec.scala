package com.example.banking.http

import java.time.Instant
import java.util.UUID

import com.example.banking.domain.{AccountId, Money}
import com.example.banking.infrastructure.InMemoryAccountRepository
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

  addServlet(new AdminServlet(operations), "/admin/*")

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
