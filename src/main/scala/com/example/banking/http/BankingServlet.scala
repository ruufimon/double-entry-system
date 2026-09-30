package com.example.banking.http

import scala.util.control.NonFatal

import com.example.banking.application.{DepositService, WithdrawService}
import com.example.banking.domain.BankingError
import org.json4s.*
import org.scalatra.ScalatraServlet
import org.scalatra.json.JacksonJsonSupport

final case class DepositRequest(amount: BigDecimal)
final case class DepositResponse(accountId: String, balance: BigDecimal)
final case class WithdrawalRequest(amount: BigDecimal)
final case class WithdrawalResponse(accountId: String, balance: BigDecimal)
final case class ErrorResponse(error: String, message: String)

final class BankingServlet(depositService: DepositService, withdrawService: WithdrawService)
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

  private def errorStatus(error: BankingError): Int =
    error match
      case BankingError.AccountNotFound(_)       => 404
      case BankingError.InsufficientFunds(_, _)  => 409
      case _                                      => 400
