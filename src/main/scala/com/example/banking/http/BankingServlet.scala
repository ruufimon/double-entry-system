package com.example.banking.http

import scala.util.control.NonFatal

import com.example.banking.application.{BillPaymentService, DepositService, WithdrawService}
import com.example.banking.domain.BankingError
import org.json4s.*
import org.scalatra.ScalatraServlet
import org.scalatra.json.JacksonJsonSupport

final case class DepositRequest(amount: BigDecimal)
final case class DepositResponse(accountId: String, balance: BigDecimal)
final case class WithdrawalRequest(amount: BigDecimal)
final case class WithdrawalResponse(accountId: String, balance: BigDecimal)
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
final case class BillPaymentConfirmationResponse(
    paymentId: String,
    inquiryId: String,
    accountId: String,
    billerCode: String,
    amount: BigDecimal,
    resultingBalance: BigDecimal,
    billerReceiptCode: String,
    paidAt: String
)
final case class ErrorResponse(error: String, message: String)

final class BankingServlet(
    depositService: DepositService,
    withdrawService: WithdrawService,
    billPaymentService: BillPaymentService
)
    extends ScalatraServlet
    with JacksonJsonSupport:

  override protected implicit lazy val jsonFormats: Formats = DefaultFormats

  before() {
    contentType = formats("json")
  }

  post("/:accountId/deposits") {
    val depositResult = for
      request <- parseDepositRequest()
      result <- depositService.deposit(params("accountId"), request.amount)
    yield DepositResponse(result.account.id.value, result.account.balance)

    depositResult match
      case Right(depositResponse) =>
        status = 200
        depositResponse
      case Left(bankingError) =>
        status = errorStatus(bankingError)
        ErrorResponse(bankingError.code, bankingError.message)
  }

  post("/:accountId/withdrawals") {
    val withdrawalResult = for
      request <- parseWithdrawalRequest()
      result <- withdrawService.withdraw(params("accountId"), request.amount)
    yield WithdrawalResponse(result.account.id.value, result.account.balance)

    withdrawalResult match
      case Right(withdrawalResponse) =>
        status = 200
        withdrawalResponse
      case Left(bankingError) =>
        status = errorStatus(bankingError)
        ErrorResponse(bankingError.code, bankingError.message)
  }

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
        BillPaymentConfirmationResponse(
          paymentId = result.event.paymentId.value.toString,
          inquiryId = result.inquiry.inquiryId.value.toString,
          accountId = result.account.id.value,
          billerCode = result.inquiry.debt.billerCode.value,
          amount = result.event.amount.amount,
          resultingBalance = result.account.balance,
          billerReceiptCode = result.billerReceipt.receiptCode,
          paidAt = result.billerReceipt.paidAt.toString
        )
      }

    respond(confirmationResult)
  }

  private def parseDepositRequest(): Either[BankingError, DepositRequest] =
    try Right(parsedBody.extract[DepositRequest])
    catch
      case NonFatal(_) =>
        Left(
          BankingError.InvalidRequest(
            "Request body must be valid JSON containing a numeric amount"
          )
        )

  private def parseWithdrawalRequest(): Either[BankingError, WithdrawalRequest] =
    try Right(parsedBody.extract[WithdrawalRequest])
    catch
      case NonFatal(_) =>
        Left(
          BankingError.InvalidRequest(
            "Request body must be valid JSON containing a numeric amount"
          )
        )

  private def parseBillPaymentInquiryRequest()
      : Either[BankingError, BillPaymentInquiryRequest] =
    try Right(parsedBody.extract[BillPaymentInquiryRequest])
    catch
      case NonFatal(_) =>
        Left(
          BankingError.InvalidRequest(
            "Request body must contain billerCode, referenceCode1, and referenceCode2"
          )
        )

  private def respond[A](result: Either[BankingError, A]): Any =
    result match
      case Right(responseBody) =>
        status = 200
        responseBody
      case Left(bankingError) =>
        status = errorStatus(bankingError)
        ErrorResponse(bankingError.code, bankingError.message)

  private def errorStatus(error: BankingError): Int =
    error match
      case BankingError.AccountNotFound(_) |
          BankingError.BillerNotFound(_) |
          BankingError.BillNotFound |
          BankingError.BillPaymentInquiryNotFound => 404
      case BankingError.InsufficientFunds(_, _) |
          BankingError.BillNotPayable |
          BankingError.BillPaymentAlreadyCompleted |
          BankingError.BillPaymentInProgress => 409
      case BankingError.BillPaymentInquiryExpired => 410
      case BankingError.BillerSettlementFailed(_) => 502
      case _                                       => 400
