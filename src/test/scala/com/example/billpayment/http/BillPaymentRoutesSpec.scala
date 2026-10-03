package com.example.billpayment.http

import java.time.{Clock, Duration}
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

import com.example.banking.application.{AccountMessageHandler, DepositService}
import com.example.banking.domain.AccountId
import com.example.banking.infrastructure.{InMemoryAccountRepository, LocalMessageBus}
import com.example.banking.ledger.LedgerBackedAccountOperations
import com.example.banking.ledger.infrastructure.InMemoryLedgerRepository
import com.example.billpayment.application.{BillPaymentProcessManager, BillPaymentService}
import com.example.billpayment.infrastructure.{
  BillSeed,
  InMemoryBillerGateway,
  InMemoryBillPaymentInquiryRepository,
  InMemoryBillPaymentProcessRepository
}
import org.json4s.*
import org.json4s.jackson.JsonMethods.parse
import org.scalatra.ScalatraServlet
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.test.scalatest.ScalatraFunSuite

final class BillPaymentRoutesSpec extends ScalatraFunSuite:
  private implicit val testJsonFormats: Formats = DefaultFormats
  private val ledgerRepository = new InMemoryLedgerRepository()
  private val accountOperations = new LedgerBackedAccountOperations(
    ledgerRepository,
    new InMemoryAccountRepository()
  )
  private val messageBus = new LocalMessageBus()
  private val depositService = new DepositService(
    accountOperations,
    messageBus,
    Clock.systemUTC(),
    () => UUID.randomUUID()
  )
  private val billerGateway = new InMemoryBillerGateway(
    Vector(
      BillSeed("demo-biller", "customer-001", "invoice-001", BigDecimal("100.00")),
      BillSeed("demo-biller", "customer-002", "invoice-002", BigDecimal("75.00"))
    )
  )
  private val inquiryRepository = new InMemoryBillPaymentInquiryRepository()
  private val processRepository = new InMemoryBillPaymentProcessRepository()
  private val service = new BillPaymentService(
    billerGateway,
    inquiryRepository,
    processRepository,
    messageBus,
    Clock.systemUTC(),
    () => UUID.randomUUID(),
    () => UUID.randomUUID()
  )
  new AccountMessageHandler(accountOperations, messageBus, Clock.systemUTC()).subscribe()
  new BillPaymentProcessManager(
    billerGateway,
    inquiryRepository,
    processRepository,
    messageBus,
    Clock.systemUTC()
  ).subscribe()

  addServlet(new TestBillPaymentServlet(service), "/accounts/*")

  test("inquiry and confirmation complete the quoted debt") {
    val inquiryId = new AtomicReference[String]()
    val paymentId = new AtomicReference[String]()
    openAccount("bill-account")
    depositService.deposit("bill-account", BigDecimal("150.00"))

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
      status shouldBe 202
      (parse(body) \ "accountId").extract[String] shouldBe "bill-account"
      (parse(body) \ "status").extract[String] shouldBe "awaiting_account_charge"
      paymentId.set((parse(body) \ "paymentId").extract[String])
    }

    messageBus.awaitIdle(Duration.ofSeconds(3)) shouldBe true

    get(s"/accounts/bill-account/bill-payments/${paymentId.get()}") {
      status shouldBe 200
      (parse(body) \ "status").extract[String] shouldBe "completed"
      (parse(body) \ "billerCode").extract[String] shouldBe "demo-biller"
      (parse(body) \ "amount").extract[BigDecimal] shouldBe BigDecimal("100.00")
      (parse(body) \ "resultingBalance").extract[BigDecimal] shouldBe BigDecimal("50.00")
      (parse(body) \ "billerReceiptCode").extract[String] should not be empty
    }
  }

  test("inquiry rejects unknown bill references") {
    openAccount("reference-account")
    depositService.deposit("reference-account", BigDecimal("100.00"))

    postJson(
      "/accounts/reference-account/bill-payments/inquiries",
      """{"billerCode":"demo-biller","referenceCode1":"unknown","referenceCode2":"unknown"}"""
    ) {
      status shouldBe 404
      (parse(body) \ "error").extract[String] shouldBe "bill_not_found"
    }
  }

  test("payment status exposes an asynchronous account rejection") {
    val inquiryId = new AtomicReference[String]()
    val paymentId = new AtomicReference[String]()

    postJson(
      "/accounts/missing-account/bill-payments/inquiries",
      """{"billerCode":"demo-biller","referenceCode1":"customer-002","referenceCode2":"invoice-002"}"""
    ) {
      status shouldBe 200
      inquiryId.set((parse(body) \ "inquiryId").extract[String])
    }

    postJson(
      s"/accounts/missing-account/bill-payments/${inquiryId.get()}/confirm",
      "{}"
    ) {
      status shouldBe 202
      paymentId.set((parse(body) \ "paymentId").extract[String])
    }
    messageBus.awaitIdle(Duration.ofSeconds(3)) shouldBe true

    get(s"/accounts/missing-account/bill-payments/${paymentId.get()}") {
      status shouldBe 200
      (parse(body) \ "status").extract[String] shouldBe "failed"
      (parse(body) \ "failure" \ "error").extract[String] shouldBe "account_not_found"
    }

    get(s"/accounts/another-account/bill-payments/${paymentId.get()}") {
      status shouldBe 404
      (parse(body) \ "error").extract[String] shouldBe "bill_payment_not_found"
    }
  }

  private def postJson(path: String, jsonBody: String)(assertions: => Unit): Unit =
    post(path, jsonBody, Map("Content-Type" -> "application/json"))(assertions)

  private def openAccount(rawAccountId: String): Unit =
    val accountId = AccountId.from(rawAccountId).getOrElse(fail("Expected valid account ID"))
    accountOperations.open(accountId).isRight shouldBe true

private final class TestBillPaymentServlet(
    protected val billPaymentService: BillPaymentService
) extends ScalatraServlet
    with JacksonJsonSupport
    with BillPaymentRoutes:
  override protected implicit lazy val jsonFormats: Formats = DefaultFormats

  before() {
    contentType = formats("json")
  }
