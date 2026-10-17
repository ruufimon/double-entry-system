package com.example.biller

import java.time.Duration

import org.json4s.*
import org.json4s.jackson.JsonMethods.parse
import org.scalatest.BeforeAndAfterEach
import org.scalatra.test.scalatest.ScalatraFunSuite

final class FakeBillerServletSpec extends ScalatraFunSuite with BeforeAndAfterEach:
  private implicit val jsonFormats: Formats = DefaultFormats
  private val store = new FakeBillerStore(FakeBillerStore.DefaultSeed, initialLatencyMs = 0L)
  private val jsonHeaders = Map("Content-Type" -> "application/json")

  addServlet(new FakeBillerServlet(store, Duration.ofMillis(50)), "/*")

  override protected def beforeEach(): Unit =
    super.beforeEach()
    store.reset()
    store.setLatencyMs(0L)

  test("GET /billers/:billerCode/bills returns the current debt") {
    get(billPath("invoice-001")) {
      status shouldBe 200
      val bill = parse(body).extract[FakeBillResponse]
      bill.amount shouldBe BigDecimal("100.00")
      bill.status shouldBe "unpaid"
    }
  }

  test("inquiry distinguishes an unknown biller from an unknown bill") {
    get("/billers/unknown-biller/bills?referenceCode1=customer-001&referenceCode2=invoice-001") {
      status shouldBe 404
      errorCode shouldBe "biller_not_found"
    }
    get(billPath("missing-invoice")) {
      status shouldBe 404
      errorCode shouldBe "bill_not_found"
    }
  }

  test("inquiry requires both reference codes") {
    get("/billers/demo-biller/bills?referenceCode1=customer-001") {
      status shouldBe 400
      errorCode shouldBe "invalid_request"
    }
  }

  test("payment settles the bill once and replays the receipt for the same payment ID") {
    val receiptCode = pay("invoice-001", "payment-1", "100.00") {
      status shouldBe 200
      parse(body).extract[FakeReceiptResponse].receiptCode
    }
    pay("invoice-001", "payment-1", "100.00") {
      status shouldBe 200
      parse(body).extract[FakeReceiptResponse].receiptCode shouldBe receiptCode
    }
    pay("invoice-001", "payment-2", "100.00") {
      status shouldBe 409
      errorCode shouldBe "bill_not_payable"
    }
    get(billPath("invoice-001")) {
      status shouldBe 409
    }
  }

  test("payment rejects an amount that differs from the debt") {
    pay("invoice-001", "payment-1", "99.00") {
      status shouldBe 422
      errorCode shouldBe "amount_mismatch"
    }
  }

  test("failure scenarios do not mark the bill paid") {
    pay("invoice-decline", "payment-1", "40.00") {
      status shouldBe 422
      errorCode shouldBe "payment_declined"
    }
    pay("invoice-error", "payment-2", "40.00") {
      status shouldBe 500
    }
    pay("invoice-timeout", "payment-3", "40.00") {
      status shouldBe 504
    }
    store.all.filter(_.scenario != BillerScenario.Ok).exists(_.paid) shouldBe false
  }

  test("inquiry_error scenario fails the inquiry") {
    upsert("""{"billerCode":"demo-biller","referenceCode1":"customer-001",
             |"referenceCode2":"invoice-broken","amount":10.00,
             |"scenario":"inquiry_error"}""".stripMargin) {
      status shouldBe 200
    }
    get(billPath("invoice-broken")) {
      status shouldBe 500
    }
  }

  test("admin can add, list, delete, and reset bills") {
    upsert("""{"billerCode":"water-co","referenceCode1":"meter-9",
             |"referenceCode2":"2026-10","amount":12.50}""".stripMargin) {
      status shouldBe 200
      parse(body).extract[FakeBillResponse].scenario shouldBe "ok"
    }
    get("/admin/bills") {
      status shouldBe 200
      parse(body).extract[Vector[FakeBillResponse]].map(_.billerCode) should contain("water-co")
    }
    delete("/admin/bills/water-co/meter-9/2026-10") {
      status shouldBe 204
    }
    pay("invoice-001", "payment-1", "100.00") {
      status shouldBe 200
    }
    post("/admin/reset") {
      status shouldBe 200
      val bills = parse(body).extract[Vector[FakeBillResponse]]
      bills should have size FakeBillerStore.DefaultSeed.size
      bills.forall(_.status == "unpaid") shouldBe true
    }
  }

  test("admin rejects an unknown scenario") {
    upsert("""{"billerCode":"demo-biller","referenceCode1":"a","referenceCode2":"b",
             |"amount":1.00,"scenario":"explode"}""".stripMargin) {
      status shouldBe 400
    }
  }

  test("global latency delays biller responses") {
    put("/admin/config", """{"latencyMs":150}""".getBytes, jsonHeaders) {
      status shouldBe 200
    }
    val startedAt = System.nanoTime()
    get(billPath("invoice-001")) {
      status shouldBe 200
    }
    Duration.ofNanos(System.nanoTime() - startedAt).toMillis should be >= 150L
  }

  private def billPath(referenceCode2: String): String =
    s"/billers/demo-biller/bills?referenceCode1=customer-001&referenceCode2=$referenceCode2"

  private def pay[A](referenceCode2: String, paymentId: String, amount: String)(
      assertions: => A
  ): A =
    post(
      "/billers/demo-biller/payments",
      s"""{"paymentId":"$paymentId","referenceCode1":"customer-001",
         |"referenceCode2":"$referenceCode2","amount":$amount,
         |"paidAt":"2026-10-17T00:00:00Z"}""".stripMargin.getBytes,
      jsonHeaders
    )(assertions)

  private def upsert[A](requestBody: String)(assertions: => A): A =
    put("/admin/bills", requestBody.getBytes, jsonHeaders)(assertions)

  private def errorCode: String = (parse(body) \ "error").extract[String]
