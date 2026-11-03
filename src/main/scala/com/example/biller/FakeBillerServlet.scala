package com.example.biller

import com.example.http.JsonErrors
import java.time.{Duration, Instant}

import scala.util.Try
import scala.util.control.NonFatal

import org.json4s.*
import org.scalatra.ScalatraServlet
import org.scalatra.json.JacksonJsonSupport

final case class FakeBillResponse(
    billerCode: String,
    referenceCode1: String,
    referenceCode2: String,
    amount: BigDecimal,
    status: String,
    scenario: String,
    // BigInt rather than Long: json4s on Scala 3 cannot reflect Option of a primitive.
    latencyMs: Option[BigInt]
)

final case class FakePaymentRequest(
    paymentId: String,
    referenceCode1: String,
    referenceCode2: String,
    amount: BigDecimal,
    paidAt: Option[String]
)

final case class FakeReceiptResponse(receiptCode: String, paidAt: String)

final case class FakeBillUpsertRequest(
    billerCode: String,
    referenceCode1: String,
    referenceCode2: String,
    amount: BigDecimal
)

final case class FakeBillerConfig(latencyMs: Long)

final case class FakeBillerErrorResponse(error: String, message: String)

/** HTTP API of a fake external biller, used to demonstrate bill payment. */
final class FakeBillerServlet(store: FakeBillerStore, settleTimeoutDelay: Duration)
    extends ScalatraServlet
    with JacksonJsonSupport
    with JsonErrors:

  override protected implicit lazy val jsonFormats: Formats = DefaultFormats

  before() {
    contentType = formats("json")
  }

  get("/ping") {
    contentType = "text/plain"
    "pong"
  }

  get("/billers/:billerCode/bills") {
    val lookup = for
      referenceCode1 <- requiredParam("referenceCode1")
      referenceCode2 <- requiredParam("referenceCode2")
    yield FakeBillKey(params("billerCode"), referenceCode1, referenceCode2)

    lookup match
      case Left(response) => response
      case Right(key) =>
        store.find(key) match
          case Left(error) =>
            simulateLatency(None)
            errorResponse(error)
          case Right(bill) =>
            simulateLatency(bill.latencyMs)
            if bill.scenario == BillerScenario.InquiryError then
              failure(500, "biller_error", "Simulated biller inquiry failure")
            else if bill.paid then errorResponse(FakeBillerError.BillNotPayable)
            else toBillResponse(bill)
  }

  post("/billers/:billerCode/payments") {
    parseBody[FakePaymentRequest] match
      case Left(response) => response
      case Right(request) =>
        val key = FakeBillKey(params("billerCode"), request.referenceCode1, request.referenceCode2)
        store.find(key) match
          case Left(error) =>
            simulateLatency(None)
            errorResponse(error)
          case Right(bill) =>
            simulateLatency(bill.latencyMs)
            bill.scenario match
              case BillerScenario.SettleDeclined =>
                failure(422, "payment_declined", "Simulated biller payment decline")
              case BillerScenario.SettleError =>
                failure(500, "biller_error", "Simulated biller settlement failure")
              case BillerScenario.SettleTimeout =>
                Thread.sleep(settleTimeoutDelay.toMillis)
                failure(504, "biller_timeout", "Simulated biller settlement timeout")
              case BillerScenario.Ok | BillerScenario.InquiryError =>
                val paidAt = request.paidAt
                  .flatMap(raw => Try(Instant.parse(raw)).toOption)
                  .getOrElse(Instant.now())
                store.settle(key, request.paymentId, request.amount, paidAt) match
                  case Left(error) => errorResponse(error)
                  case Right(receipt) =>
                    FakeReceiptResponse(receipt.receiptCode, receipt.paidAt.toString)
  }

  get("/admin/bills") {
    store.all.map(toBillResponse)
  }

  put("/admin/bills") {
    parseBody[FakeBillUpsertRequest].flatMap(request => toBill(request, parsedBody)) match
      case Left(response) => response
      case Right(bill) =>
        store.upsert(bill)
        toBillResponse(bill)
  }

  delete("/admin/bills/:billerCode/:referenceCode1/:referenceCode2") {
    val key = FakeBillKey(
      params("billerCode"),
      params("referenceCode1"),
      params("referenceCode2")
    )
    if store.remove(key) then
      status = 204
      ()
    else errorResponse(FakeBillerError.BillNotFound)
  }

  post("/admin/reset") {
    store.reset()
    store.all.map(toBillResponse)
  }

  get("/admin/config") {
    FakeBillerConfig(store.latencyMs)
  }

  put("/admin/config") {
    parseBody[FakeBillerConfig] match
      case Left(response) => response
      case Right(config) if config.latencyMs < 0 =>
        failure(400, "invalid_request", "latencyMs must not be negative")
      case Right(config) =>
        store.setLatencyMs(config.latencyMs)
        config
  }

  /** Optional fields are read from the raw body; see the note on FakeBillResponse. */
  private def toBill(
      request: FakeBillUpsertRequest,
      body: JValue
  ): Either[FakeBillerErrorResponse, FakeBill] =
    val scenarioName = (body \ "scenario").extractOpt[String].getOrElse(BillerScenario.Ok.wireName)
    val latencyMs = (body \ "latencyMs").extractOpt[Long]
    BillerScenario.fromWireName(scenarioName) match
      case None =>
        Left(failure(400, "invalid_request", s"Unknown scenario '$scenarioName'"))
      case Some(_) if request.amount <= 0 =>
        Left(failure(400, "invalid_request", "amount must be positive"))
      case Some(_) if latencyMs.exists(_ < 0) =>
        Left(failure(400, "invalid_request", "latencyMs must not be negative"))
      case Some(scenario) =>
        Right(
          FakeBill(
            FakeBillKey(request.billerCode, request.referenceCode1, request.referenceCode2),
            request.amount,
            (body \ "paid").extractOpt[Boolean].getOrElse(false),
            scenario,
            latencyMs
          )
        )

  private def toBillResponse(bill: FakeBill): FakeBillResponse =
    FakeBillResponse(
      billerCode = bill.key.billerCode,
      referenceCode1 = bill.key.referenceCode1,
      referenceCode2 = bill.key.referenceCode2,
      amount = bill.amount,
      status = if bill.paid then "paid" else "unpaid",
      scenario = bill.scenario.wireName,
      latencyMs = bill.latencyMs.map(BigInt(_))
    )

  private def simulateLatency(billLatencyMs: Option[Long]): Unit =
    val delayMs = billLatencyMs.getOrElse(store.latencyMs)
    if delayMs > 0 then Thread.sleep(delayMs)

  private def requiredParam(name: String): Either[FakeBillerErrorResponse, String] =
    params.get(name).filter(_.nonEmpty).toRight(
      failure(400, "invalid_request", s"Query parameter '$name' is required")
    )

  private def parseBody[A: Manifest]: Either[FakeBillerErrorResponse, A] =
    try Right(parsedBody.extract[A])
    catch
      case NonFatal(_) =>
        Left(failure(400, "invalid_request", "Request body is missing or malformed"))

  private def errorResponse(error: FakeBillerError): FakeBillerErrorResponse =
    val httpStatus = error match
      case FakeBillerError.BillerNotFound(_) | FakeBillerError.BillNotFound => 404
      case FakeBillerError.BillNotPayable                                  => 409
      case FakeBillerError.AmountMismatch(_)                               => 422
    failure(httpStatus, error.code, error.message)

  private def failure(httpStatus: Int, code: String, message: String): FakeBillerErrorResponse =
    status = httpStatus
    FakeBillerErrorResponse(code, message)
