package com.example.billpayment.http

import scala.util.control.NonFatal

import com.example.banking.domain.BankingError
import com.example.billpayment.application.BillPaymentService
import com.example.billpayment.domain.{
  BillPaymentError,
  BillPaymentFailure,
  BillPaymentProcess,
  BillPaymentStatus
}
import com.example.domain.DomainError
import org.json4s.*
import org.scalatra.ScalatraServlet
import org.scalatra.json.JacksonJsonSupport

final case class BillPaymentInquiryRequest(
    billerCode: String,
    referenceCode1: String,
    referenceCode2: String
)

final case class BillPaymentInquiryResponse(
    inquiryId: String,
    billerCode: String,
    referenceCode1: String,
    referenceCode2: String,
    currentDebt: BigDecimal,
    expiresAt: String
)

final case class BillPaymentAcceptedResponse(
    paymentId: String,
    inquiryId: String,
    accountId: String,
    status: String
)

final case class BillPaymentFailureResponse(error: String, message: String)

final case class BillPaymentStatusResponse(
    paymentId: String,
    inquiryId: String,
    accountId: String,
    billerCode: String,
    amount: BigDecimal,
    currency: String,
    status: String,
    resultingBalance: Option[BigDecimal],
    billerReceiptCode: Option[String],
    paidAt: Option[String],
    failure: Option[BillPaymentFailureResponse]
)

final case class BillPaymentErrorResponse(error: String, message: String)

trait BillPaymentRoutes:
  self: ScalatraServlet & JacksonJsonSupport =>

  protected def billPaymentService: BillPaymentService

  post("/:accountId/bill-payments/inquiries") {
    val inquiryResult = for
      request <- parseBillPaymentInquiryRequest()
      result <- billPaymentService.inquire(
        params("accountId"),
        request.billerCode,
        request.referenceCode1,
        request.referenceCode2
      )
    yield
      val inquiry = result.inquiry
      BillPaymentInquiryResponse(
        inquiryId = inquiry.inquiryId.value.toString,
        billerCode = inquiry.debt.billerCode.value,
        referenceCode1 = inquiry.debt.referenceCode1.value,
        referenceCode2 = inquiry.debt.referenceCode2.value,
        currentDebt = inquiry.debt.amount.amount,
        expiresAt = inquiry.expiresAt.toString
      )

    respond(inquiryResult)
  }

  post("/:accountId/bill-payments/:inquiryId/confirm") {
    val confirmationResult = billPaymentService
      .confirm(params("accountId"), params("inquiryId"))
      .map { result =>
        BillPaymentAcceptedResponse(
          paymentId = result.process.paymentId.value.toString,
          inquiryId = result.process.inquiryId.value.toString,
          accountId = result.process.accountId.value,
          status = statusName(result.process.status)
        )
      }

    confirmationResult match
      case Right(responseBody) =>
        status = 202
        responseBody
      case Left(error) =>
        status = errorStatus(error)
        BillPaymentErrorResponse(error.code, error.message)
  }

  get("/:accountId/bill-payments/:paymentId") {
    val paymentResult = billPaymentService
      .payment(params("accountId"), params("paymentId"))
      .map(toStatusResponse)

    respond(paymentResult)
  }

  private def parseBillPaymentInquiryRequest()
      : Either[DomainError, BillPaymentInquiryRequest] =
    try Right(parsedBody.extract[BillPaymentInquiryRequest])
    catch
      case NonFatal(_) =>
        Left(
          BankingError.InvalidRequest(
            "Request body must contain billerCode, referenceCode1, and referenceCode2"
          )
        )

  private def respond[A](result: Either[DomainError, A]): Any =
    result match
      case Right(responseBody) =>
        status = 200
        responseBody
      case Left(domainError) =>
        status = errorStatus(domainError)
        BillPaymentErrorResponse(domainError.code, domainError.message)

  private def toStatusResponse(process: BillPaymentProcess): BillPaymentStatusResponse =
    BillPaymentStatusResponse(
      paymentId = process.paymentId.value.toString,
      inquiryId = process.inquiryId.value.toString,
      accountId = process.accountId.value,
      billerCode = process.debt.billerCode.value,
      amount = process.debt.amount.amount,
      currency = "THB",
      status = statusName(process.status),
      resultingBalance = process.resultingBalance,
      billerReceiptCode = process.billerReceipt.map(_.receiptCode),
      paidAt = process.billerReceipt.map(_.paidAt.toString),
      failure = process.failure.map(toFailureResponse)
    )

  private def toFailureResponse(failure: BillPaymentFailure): BillPaymentFailureResponse =
    BillPaymentFailureResponse(failure.code, failure.message)

  private def statusName(status: BillPaymentStatus): String =
    status match
      case BillPaymentStatus.AwaitingAccountCharge => "awaiting_account_charge"
      case BillPaymentStatus.Settling             => "settling"
      case BillPaymentStatus.Reversing             => "reversing"
      case BillPaymentStatus.Completed             => "completed"
      case BillPaymentStatus.Failed                => "failed"
      case BillPaymentStatus.ManualReview           => "manual_review"

  private def errorStatus(error: DomainError): Int =
    error match
      case BankingError.AccountNotFound(_) |
          BillPaymentError.BillerNotFound(_) |
          BillPaymentError.BillNotFound |
          BillPaymentError.InquiryNotFound |
          BillPaymentError.PaymentNotFound => 404
      case BankingError.InsufficientFunds(_, _) |
          BillPaymentError.BillNotPayable |
          BillPaymentError.AlreadyCompleted |
          BillPaymentError.InProgress => 409
      case BillPaymentError.InquiryExpired      => 410
      case BillPaymentError.SettlementFailed(_) => 502
      case BillPaymentError.CompensationFailed(_) => 500
      case _                                    => 400
