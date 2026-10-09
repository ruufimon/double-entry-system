package com.example.banking.http

import scala.util.control.NonFatal

import com.example.banking.application.{AccountService, DepositService, TransferService, WithdrawService}
import com.example.banking.domain.{AccountId, BankingError, Currency}
import com.example.banking.ledger.LedgerError
import com.example.banking.ports.AccountOperations
import com.example.billpayment.application.BillPaymentService
import com.example.billpayment.http.BillPaymentRoutes
import com.example.domain.DomainError
import org.json4s.*
import org.scalatra.ScalatraServlet
import org.scalatra.json.JacksonJsonSupport

final case class DepositRequest(amount: BigDecimal)
final case class AccountResponse(
    accountId: String,
    currency: String,
    balance: BigDecimal,
    status: String
)
final case class DepositResponse(accountId: String, balance: BigDecimal)
final case class WithdrawalRequest(amount: BigDecimal)
final case class WithdrawalResponse(accountId: String, balance: BigDecimal)
final case class TransferRequest(destinationAccountId: String, amount: BigDecimal)
final case class TransferResponse(
    transferId: String,
    sourceAccountId: String,
    destinationAccountId: String,
    amount: BigDecimal,
    currency: String,
    sourceBalance: BigDecimal,
    occurredAt: String
)
final case class AccountBalanceResponse(accountId: String, currency: String, balance: BigDecimal)
final case class AccountOverviewResponse(
    accountId: String,
    currency: String,
    balance: BigDecimal,
    activities: Vector[AccountActivityResponse]
)
final case class AccountActivityResponse(
    transactionId: String,
    operation: String,
    effect: String,
    amount: BigDecimal,
    currency: String,
    balanceAfter: BigDecimal,
    occurredAt: String,
    status: String,
    originalTransactionId: Option[String],
    counterpartyAccountId: Option[String]
)
final case class ErrorResponse(error: String, message: String)

final class BankingServlet(
    accountService: AccountService,
    depositService: DepositService,
    withdrawService: WithdrawService,
    transferService: TransferService,
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

  put("/:accountId") {
    accountService.open(params("accountId")) match
      case Right(opening) =>
        status = if opening.created then 201 else 200
        AccountResponse(
          accountId = opening.account.id.value,
          currency = Currency.THB.code,
          balance = opening.account.balance,
          status = "active"
        )
      case Left(error) =>
        status = errorStatus(error)
        ErrorResponse(error.code, error.message)
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

  post("/:accountId/transfers") {
    val transferResult = for
      request <- parseTransferRequest()
      result <- transferService.transfer(
        params("accountId"),
        request.destinationAccountId,
        request.amount
      )
    yield TransferResponse(
      transferId = result.transferId.value.toString,
      sourceAccountId = result.sourceAccount.id.value,
      destinationAccountId = result.destinationAccount.id.value,
      amount = result.amount.amount,
      currency = Currency.THB.code,
      sourceBalance = result.sourceAccount.balance,
      occurredAt = result.occurredAt.toString
    )

    transferResult match
      case Right(transferResponse) =>
        status = 200
        transferResponse
      case Left(error) =>
        status = errorStatus(error)
        ErrorResponse(error.code, error.message)
  }

  get("/:accountId/balance") {
    val result = for
      accountId <- AccountId.from(params("accountId"))
      account <- accountOperations.find(accountId)
    yield AccountBalanceResponse(account.id.value, "THB", account.balance)

    respond(result)
  }

  get("/:accountId/overview") {
    val result = for
      accountId <- AccountId.from(params("accountId"))
      overview <- accountOperations.overview(accountId)
    yield AccountOverviewResponse(
      accountId = overview.account.id.value,
      currency = Currency.THB.code,
      balance = overview.account.balance,
      activities = overview.activities.map(AccountResponseMapping.toActivityResponse)
    )

    respond(result)
  }

  get("/:accountId/activities") {
    val result = for
      accountId <- AccountId.from(params("accountId"))
      activities <- accountOperations.activities(accountId)
    yield activities.map(AccountResponseMapping.toActivityResponse)

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

  private def parseTransferRequest(): Either[BankingError, TransferRequest] =
    try Right(parsedBody.extract[TransferRequest])
    catch
      case NonFatal(_) =>
        Left(
          BankingError.InvalidRequest(
            "Request body must contain destinationAccountId and a numeric amount"
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

  private def errorStatus(error: DomainError): Int =
    error match
      case BankingError.AccountNotFound(_)      => 404
      case BankingError.InsufficientFunds(_, _) => 409
      case LedgerError.DuplicateTransaction(_) |
          LedgerError.TransactionAlreadyReversed(_) => 409
      case LedgerError.TransactionNotFound(_) => 404
      case _                                     => 400
