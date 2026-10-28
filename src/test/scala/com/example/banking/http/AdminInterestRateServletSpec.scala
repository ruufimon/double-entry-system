package com.example.banking.http

import java.nio.charset.StandardCharsets
import java.time.{Clock, Instant, ZoneId}
import java.util.Base64

import com.example.banking.application.InterestRateService
import com.example.banking.infrastructure.{InMemoryAccountRepository, InMemoryInterestSettingsRepository}
import com.example.banking.ledger.LedgerBackedAccountOperations
import com.example.banking.ledger.infrastructure.InMemoryLedgerRepository
import org.json4s.*
import org.json4s.jackson.JsonMethods.parse
import org.scalatra.test.scalatest.ScalatraFunSuite

final class AdminInterestRateServletSpec extends ScalatraFunSuite:
  private implicit val jsonFormats: Formats = DefaultFormats
  private val accountRepository = new InMemoryAccountRepository()
  private val ledgerRepository = new InMemoryLedgerRepository()
  private val service = new InterestRateService(
    ledgerRepository,
    accountRepository,
    new InMemoryInterestSettingsRepository(),
    Clock.fixed(Instant.parse("2026-10-28T05:00:00Z"), ZoneId.of("UTC")),
    ZoneId.of("Asia/Bangkok")
  )
  private val expectedAuthorization = basicAuth("secret")

  addServlet(
    new AdminServlet(
      new LedgerBackedAccountOperations(ledgerRepository, accountRepository),
      Some(service),
      Some("secret")
    ),
    "/admin/*"
  )

  test("rate settings require the configured admin password") {
    get("/admin/interest-rate") {
      status shouldBe 401
      response.getHeader("WWW-Authenticate") should include("Basic")
      (parse(body) \ "error").extract[String] shouldBe "admin_auth_required"
    }

    get("/admin/interest-rate", Map.empty, Map("Authorization" -> basicAuth("wrong"))) {
      status shouldBe 401
    }
  }

  test("GET and PUT rate settings return current and scheduled rates") {
    get("/admin/interest-rate", Map.empty, Map("Authorization" -> expectedAuthorization)) {
      status shouldBe 200
      val overview = parse(body)
      (overview \ "currentRate").extract[BigDecimal] shouldBe BigDecimal("0.00")
      (overview \ "nextEffectiveFrom").extract[String] shouldBe "2026-11-01"
      (overview \ "scheduledRate").extract[Option[BigDecimal]] shouldBe None
    }

    put(
      "/admin/interest-rate",
      """{"rate":3.65}""",
      Map("Content-Type" -> "application/json", "Authorization" -> expectedAuthorization)
    ) {
      status shouldBe 200
      val overview = parse(body)
      (overview \ "currentRate").extract[BigDecimal] shouldBe BigDecimal("0.00")
      (overview \ "scheduledRate").extract[Option[BigDecimal]] shouldBe Some(BigDecimal("3.65"))
      (overview \ "scheduledEffectiveFrom").extract[String] shouldBe "2026-11-01"
      (overview \ "history").children.headOption.map(row => (row \ "status").extract[String]) shouldBe
        Some("scheduled")
    }
  }

  test("invalid rates are rejected and are not persisted") {
    val scheduledBefore = service.overview.scheduledRate
    put(
      "/admin/interest-rate",
      """{"rate":-1}""",
      Map("Content-Type" -> "application/json", "Authorization" -> expectedAuthorization)
    ) {
      status shouldBe 400
      (parse(body) \ "error").extract[String] shouldBe "invalid_interest_rate"
    }
    service.overview.scheduledRate shouldBe scheduledBefore
  }

  private def basicAuth(password: String): String =
    val credentials = s"admin:$password".getBytes(StandardCharsets.UTF_8)
    s"Basic ${Base64.getEncoder.encodeToString(credentials)}"
