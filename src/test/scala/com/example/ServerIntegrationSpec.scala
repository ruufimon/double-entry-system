package com.example

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.time.Duration

import org.json4s.*
import org.json4s.jackson.JsonMethods.parse
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class ServerIntegrationSpec extends AnyFunSuite with Matchers with BeforeAndAfterEach:
  private implicit val jsonFormats: Formats = DefaultFormats
  private val client = HttpClient
    .newBuilder()
    .connectTimeout(Duration.ofSeconds(2))
    .build()
  private var apiServer: BankingApiServer = _

  override protected def beforeEach(): Unit =
    super.beforeEach()
    apiServer = Server.create(0)
    apiServer.start()

  override protected def afterEach(): Unit =
    try
      if apiServer != null then apiServer.close()
    finally
      apiServer = null
      super.afterEach()

  test("real API completes a bill payment and exposes its ledger effect") {
    putJson("/accounts/integration-success", "{}").statusCode() shouldBe 201
    postJson(
      "/accounts/integration-success/deposits",
      """{"amount":150.00}"""
    ).statusCode() shouldBe 200

    val inquiry = postJson(
      "/accounts/integration-success/bill-payments/inquiries",
      demoBillInquiry
    )
    inquiry.statusCode() shouldBe 200
    val inquiryId = (parse(inquiry.body()) \ "inquiryId").extract[String]

    val confirmation = postJson(
      s"/accounts/integration-success/bill-payments/$inquiryId/confirm",
      "{}"
    )
    confirmation.statusCode() shouldBe 202
    val paymentId = (parse(confirmation.body()) \ "paymentId").extract[String]

    val payment = awaitTerminalPayment("integration-success", paymentId)
    (payment \ "status").extract[String] shouldBe "completed"
    (payment \ "resultingBalance").extract[BigDecimal] shouldBe BigDecimal("50.00")
    (payment \ "billerReceiptCode").extract[String] should not be empty

    val overviewResponse = get("/accounts/integration-success/overview")
    overviewResponse.statusCode() shouldBe 200
    val overview = parse(overviewResponse.body())
    (overview \ "balance").extract[BigDecimal] shouldBe BigDecimal("50.00")
    val billPayments = (overview \ "activities").children.filter { activity =>
      (activity \ "operation").extract[String] == "bill_payment"
    }
    billPayments should have size 1
    (billPayments.head \ "effect").extract[String] shouldBe "decrease"
    (billPayments.head \ "amount").extract[BigDecimal] shouldBe BigDecimal("100.00")
    (billPayments.head \ "balanceAfter").extract[BigDecimal] shouldBe BigDecimal("50.00")
    (billPayments.head \ "status").extract[String] shouldBe "posted"
  }

  test("real API rejects a bill payment without changing an insufficient balance") {
    putJson("/accounts/integration-rejected", "{}").statusCode() shouldBe 201
    postJson(
      "/accounts/integration-rejected/deposits",
      """{"amount":40.00}"""
    ).statusCode() shouldBe 200

    val inquiry = postJson(
      "/accounts/integration-rejected/bill-payments/inquiries",
      demoBillInquiry
    )
    inquiry.statusCode() shouldBe 200
    val inquiryId = (parse(inquiry.body()) \ "inquiryId").extract[String]

    val confirmation = postJson(
      s"/accounts/integration-rejected/bill-payments/$inquiryId/confirm",
      "{}"
    )
    confirmation.statusCode() shouldBe 202
    val paymentId = (parse(confirmation.body()) \ "paymentId").extract[String]

    val payment = awaitTerminalPayment("integration-rejected", paymentId)
    (payment \ "status").extract[String] shouldBe "failed"
    (payment \ "failure" \ "error").extract[String] shouldBe "insufficient_funds"

    val overviewResponse = get("/accounts/integration-rejected/overview")
    overviewResponse.statusCode() shouldBe 200
    val overview = parse(overviewResponse.body())
    (overview \ "balance").extract[BigDecimal] shouldBe BigDecimal("40.00")
    val operations = (overview \ "activities").children.map { activity =>
      (activity \ "operation").extract[String]
    }
    operations shouldBe List("deposit")
  }

  test("real API atomically transfers money between two accounts") {
    putJson("/accounts/http-transfer-source", "{}").statusCode() shouldBe 201
    putJson("/accounts/http-transfer-destination", "{}").statusCode() shouldBe 201
    postJson(
      "/accounts/http-transfer-source/deposits",
      """{"amount":80.00}"""
    ).statusCode() shouldBe 200

    val response = postJson(
      "/accounts/http-transfer-source/transfers",
      """{"destinationAccountId":"http-transfer-destination","amount":30.00}"""
    )
    response.statusCode() shouldBe 200
    val transfer = parse(response.body())
    (transfer \ "sourceBalance").extract[BigDecimal] shouldBe BigDecimal("50.00")

    val source = parse(get("/accounts/http-transfer-source/overview").body())
    val destination = parse(get("/accounts/http-transfer-destination/overview").body())
    (source \ "balance").extract[BigDecimal] shouldBe BigDecimal("50.00")
    (destination \ "balance").extract[BigDecimal] shouldBe BigDecimal("30.00")
    val outgoing = (source \ "activities").children.last
    val incoming = (destination \ "activities").children.last
    (outgoing \ "operation").extract[String] shouldBe "transfer_out"
    (outgoing \ "counterpartyAccountId").extract[String] shouldBe
      "http-transfer-destination"
    (incoming \ "operation").extract[String] shouldBe "transfer_in"
    (incoming \ "counterpartyAccountId").extract[String] shouldBe "http-transfer-source"
  }

  private def awaitTerminalPayment(accountId: String, paymentId: String): JValue =
    val deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos
    var payment = parse(get(s"/accounts/$accountId/bill-payments/$paymentId").body())
    var status = (payment \ "status").extract[String]

    while processingStatuses.contains(status) && System.nanoTime() < deadline do
      Thread.sleep(20)
      payment = parse(get(s"/accounts/$accountId/bill-payments/$paymentId").body())
      status = (payment \ "status").extract[String]

    withClue(s"Payment did not reach a terminal state: $payment") {
      processingStatuses should not contain status
    }
    payment

  private def putJson(path: String, body: String): HttpResponse[String] =
    send(HttpRequest.newBuilder(uri(path)).PUT(BodyPublishers.ofString(body)), json = true)

  private def postJson(path: String, body: String): HttpResponse[String] =
    send(HttpRequest.newBuilder(uri(path)).POST(BodyPublishers.ofString(body)), json = true)

  private def get(path: String): HttpResponse[String] =
    send(HttpRequest.newBuilder(uri(path)).GET(), json = false)

  private def send(
      builder: HttpRequest.Builder,
      json: Boolean
  ): HttpResponse[String] =
    val requestBuilder = builder.timeout(Duration.ofSeconds(3))
    if json then requestBuilder.header("Content-Type", "application/json")
    client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())

  private def uri(path: String): URI =
    URI.create(s"http://127.0.0.1:${apiServer.port}$path")

  private val processingStatuses = Set("awaiting_account_charge", "settling", "reversing")
  private val demoBillInquiry =
    """{"billerCode":"demo-biller","referenceCode1":"customer-001","referenceCode2":"invoice-001"}"""
