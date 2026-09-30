package com.example.banking.http

import scala.util.control.NonFatal

import com.example.banking.application.{DepositService, WithdrawService}
import com.example.banking.domain.{
  AccountActivity,
  AccountActivityStatus,
  AccountId,
  BalanceEffect,
  BankingError,
  BankingOperation
}
import com.example.banking.ledger.LedgerError
import com.example.banking.ports.AccountOperations
import com.example.billpayment.application.BillPaymentService
import com.example.billpayment.http.BillPaymentRoutes
import com.example.domain.DomainError
import org.json4s.*
import org.scalatra.ScalatraServlet
import org.scalatra.json.JacksonJsonSupport

final case class DepositRequest(amount: BigDecimal)
final case class DepositResponse(accountId: String, balance: BigDecimal)
final case class WithdrawalRequest(amount: BigDecimal)
final case class WithdrawalResponse(accountId: String, balance: BigDecimal)
final case class AccountBalanceResponse(accountId: String, currency: String, balance: BigDecimal)
final case class AccountActivityResponse(
    transactionId: String,
    operation: String,
    effect: String,
    amount: BigDecimal,
    currency: String,
    balanceAfter: BigDecimal,
    occurredAt: String,
    status: String,
    originalTransactionId: Option[String]
)
final case class ErrorResponse(error: String, message: String)

final class BankingServlet(
    depositService: DepositService,
    withdrawService: WithdrawService,
    protected val billPaymentService: BillPaymentService,
    accountOperations: AccountOperations
)
    extends ScalatraServlet
    with JacksonJsonSupport
    with BillPaymentRoutes:

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

  get("/:accountId/balance") {
    val result = for
      accountId <- AccountId.from(params("accountId"))
      account <- accountOperations.find(accountId)
    yield AccountBalanceResponse(account.id.value, "THB", account.balance)

    respond(result)
  }

  get("/:accountId/activities") {
    val result = for
      accountId <- AccountId.from(params("accountId"))
      activities <- accountOperations.activities(accountId)
    yield activities.map(toActivityResponse)

    respond(result)
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

  private def respond[A](result: Either[DomainError, A]): Any =
    result match
      case Right(responseBody) =>
        status = 200
        responseBody
      case Left(error) =>
        status = errorStatus(error)
        ErrorResponse(error.code, error.message)

  private def toActivityResponse(activity: AccountActivity): AccountActivityResponse =
    AccountActivityResponse(
      transactionId = activity.transactionId.toString,
      operation = activity.operation match
        case BankingOperation.Deposit     => "deposit"
        case BankingOperation.Withdrawal  => "withdrawal"
        case BankingOperation.BillPayment => "bill_payment"
        case BankingOperation.BillPaymentReversal => "bill_payment_reversal",
      effect = activity.effect match
        case BalanceEffect.Increase => "increase"
        case BalanceEffect.Decrease => "decrease",
      amount = activity.amount,
      currency = activity.currency.code,
      balanceAfter = activity.balanceAfter,
      occurredAt = activity.occurredAt.toString,
      status = activity.status match
        case AccountActivityStatus.Posted   => "posted"
        case AccountActivityStatus.Reversed => "reversed",
      originalTransactionId = activity.originalTransactionId.map(_.toString)
    )

  private def errorStatus(error: DomainError): Int =
    error match
      case BankingError.AccountNotFound(_)      => 404
      case BankingError.InsufficientFunds(_, _) => 409
      case LedgerError.DuplicateTransaction(_) |
          LedgerError.TransactionAlreadyReversed(_) => 409
      case LedgerError.TransactionNotFound(_) => 404
      case _                                     => 400
