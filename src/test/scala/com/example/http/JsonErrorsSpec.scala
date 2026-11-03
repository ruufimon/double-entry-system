package com.example.http

import org.scalatra.ScalatraServlet
import org.scalatra.test.scalatest.ScalatraFunSuite

final class JsonErrorsSpec extends ScalatraFunSuite:
  private final class FailingServlet extends ScalatraServlet with JsonErrors:
    get("/boom") {
      throw new IllegalStateException("secret-internal-detail")
    }

  addServlet(new FailingServlet(), "/*")

  test("an uncaught exception returns a fixed JSON 500 without the stack trace") {
    get("/boom") {
      status shouldBe 500
      header("Content-Type") should startWith("application/json")
      body shouldBe JsonErrors.InternalErrorBody
      body should not include "secret-internal-detail"
      body should not include "Exception"
    }
  }

  test("an unknown route returns a fixed JSON 404 without listing routes") {
    get("/nope") {
      status shouldBe 404
      header("Content-Type") should startWith("application/json")
      body shouldBe JsonErrors.NotFoundBody
      body should not include "/boom"
    }
  }
