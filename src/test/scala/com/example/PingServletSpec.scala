package com.example

import org.scalatra.test.scalatest.ScalatraFunSuite

final class PingServletSpec extends ScalatraFunSuite:
  addServlet(classOf[PingServlet], "/*")

  test("GET /ping returns pong") {
    get("/ping") {
      status shouldBe 200
      header("Content-Type") should startWith("text/plain")
      body shouldBe "pong"
    }
  }
