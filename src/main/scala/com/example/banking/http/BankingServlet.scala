package com.example.banking.http

import scala.util.control.NonFatal

import com.example.banking.application.DepositService
import com.example.banking.domain.BankingError
import org.json4s.*
import org.scalatra.ScalatraServlet
import org.scalatra.json.JacksonJsonSupport

final case class DepositRequest(amount: BigDecimal)
final case class DepositResponse(accountId: String, balance: BigDecimal)
final case class ErrorResponse(error: String, message: String)

final class BankingServlet(depositService: DepositService)
    extends ScalatraServlet
    with JacksonJsonSupport:

  override protected implicit lazy val jsonFormats: Formats = DefaultFormats

  before() {
    contentType = formats("json")
  }

  post("/:accountId/deposits") {
    val depositResult = for
      request <- parseDepositRequest()
      account <- depositService.deposit(params("accountId"), request.amount)
    yield DepositResponse(account.id.value, account.balance)

    depositResult match
      case Right(depositResponse) =>
        status = 200
        depositResponse
      case Left(bankingError) =>
        status = 400
        ErrorResponse(bankingError.code, bankingError.message)
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
