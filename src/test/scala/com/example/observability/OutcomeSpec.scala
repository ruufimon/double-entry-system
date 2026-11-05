package com.example.observability

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Pins the error table in spec/MonitoringSLO.md. Adding a status here means updating the spec. */
final class OutcomeSpec extends AnyFunSuite with Matchers:
  private val classes = RouteClass.values.toSeq

  test("2xx and 3xx are success for every class") {
    for cls <- classes; status <- Seq(200, 201, 202, 204, 302) do
      Outcome.of(cls, status) shouldBe Outcome.Success
  }

  test("business rejections and other 4xx are rejected for every class") {
    for cls <- classes; status <- Seq(400, 401, 403, 404, 405, 409, 410, 415, 422) do
      Outcome.of(cls, status) shouldBe Outcome.Rejected
  }

  test("502 and 503 on bill routes are a biller dependency failure") {
    Outcome.of(RouteClass.Bill, 502) shouldBe Outcome.Dependency
    Outcome.of(RouteClass.Bill, 503) shouldBe Outcome.Dependency
  }

  test("503 elsewhere is misconfiguration (admin_auth_not_configured)") {
    for cls <- classes if cls != RouteClass.Bill do
      Outcome.of(cls, 503) shouldBe Outcome.Misconfigured
  }

  test("500, 502 off bill routes and anything else 5xx are errors") {
    for cls <- classes do Outcome.of(cls, 500) shouldBe Outcome.Error
    Outcome.of(RouteClass.Money, 502) shouldBe Outcome.Error
    Outcome.of(RouteClass.Read, 504) shouldBe Outcome.Error
  }

  test("only success and rejected are good events for availability") {
    Outcome.isGood(Outcome.Success) shouldBe true
    Outcome.isGood(Outcome.Rejected) shouldBe true
    Seq(Outcome.Dependency, Outcome.Misconfigured, Outcome.Error).foreach(Outcome.isGood(_) shouldBe false)
  }
