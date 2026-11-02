package com.example.banking.http

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

import scala.util.Try
import scala.util.control.NonFatal

import com.example.banking.application.{AdjustmentCommand, AdjustmentService}
import com.example.banking.domain.{
  AccountId,
  AccountOverview,
  AdjustmentDirection,
  BalanceAdjustment,
  BankingError,
  Currency
}
import com.example.banking.ledger.LedgerError
import com.example.banking.ports.AccountOperations
import com.example.domain.DomainError
import org.json4s.*
import org.scalatra.ScalatraServlet
import org.scalatra.json.JacksonJsonSupport

final case class AdminAccountSummaryResponse(
    accountId: String,
    currency: String,
    status: String,
    balance: BigDecimal,
    activityCount: Int,
    lastActivityAt: Option[String]
)

final case class AdminAccountDetailResponse(
    accountId: String,
    currency: String,
    status: String,
    balance: BigDecimal,
    activityCount: Int,
    lastActivityAt: Option[String],
    activities: Vector[AccountActivityResponse]
)

final case class AdminAdjustmentRequest(
    direction: Option[String],
    amount: Option[BigDecimal],
    reason: Option[String],
    adjustsTransactionId: Option[String]
)

final case class AdminAdjustmentResponse(
    adjustmentId: String,
    accountId: String,
    direction: String,
    amount: BigDecimal,
    currency: String,
    reason: String,
    actor: String,
    adjustsTransactionId: Option[String],
    balanceAfter: BigDecimal,
    occurredAt: String
)

/** Admin console API. Account reads are open; adjustments require `adminToken`, and are
  * disabled when it is not configured.
  */
final class AdminServlet(
    accountOperations: AccountOperations,
    adjustmentService: AdjustmentService,
    adminToken: Option[String]
) extends ScalatraServlet
    with JacksonJsonSupport:

  override protected implicit lazy val jsonFormats: Formats = DefaultFormats

  before() {
    contentType = formats("json")
  }

  get("/accounts") {
    accountOperations.allOverviews.map(toSummaryResponse)
  }

  get("/accounts/:accountId") {
    val result = for
      accountId <- AccountId.from(params("accountId"))
      overview <- accountOperations.overview(accountId)
    yield toDetailResponse(overview)

    result match
      case Right(response) => response
      case Left(error) =>
        status = errorStatus(error)
        ErrorResponse(error.code, error.message)
  }

  post("/accounts/:accountId/adjustments") {
    withAdminToken {
      val result = for
        body <- parseAdjustmentRequest()
        adjustsTransactionId <- parseAdjustsTransactionId(body.adjustsTransactionId)
        adjustment <- adjustmentService.adjust(
          AdjustmentCommand(
            accountId = params("accountId"),
            direction = body.direction,
            amount = body.amount,
            reason = body.reason,
            actor = Option(request.getHeader("X-Admin-Actor")),
            idempotencyKey = Option(request.getHeader("Idempotency-Key")),
            adjustsTransactionId = adjustsTransactionId
          )
        )
      yield toAdjustmentResponse(adjustment)

      result match
        case Right(response) =>
          status = 201
          response
        case Left(error) =>
          status = errorStatus(error)
          ErrorResponse(error.code, error.message)
    }
  }

  get("/accounts/:accountId/adjustments") {
    withAdminToken {
      adjustmentService.adjustments(params("accountId")) match
        case Right(adjustments) => adjustments.map(toAdjustmentResponse)
        case Left(error) =>
          status = errorStatus(error)
          ErrorResponse(error.code, error.message)
    }
  }

  private def withAdminToken(action: => Any): Any =
    val presented = Option(request.getHeader("Authorization")).collect {
      case header if header.startsWith("Bearer ") => header.stripPrefix("Bearer ")
    }
    val authorized = (adminToken, presented) match
      case (Some(expected), Some(actual)) =>
        MessageDigest.isEqual(
          expected.getBytes(StandardCharsets.UTF_8),
          actual.getBytes(StandardCharsets.UTF_8)
        )
      case _ => false

    if authorized then action
    else
      status = 401
      ErrorResponse("unauthorized", "A valid admin bearer token is required")

  private def parseAdjustmentRequest(): Either[BankingError, AdminAdjustmentRequest] =
    val invalid = BankingError.InvalidRequest(
      "Request body must be a JSON object with direction, amount, and reason"
    )
    parsedBody match
      case json: JObject =>
        try Right(json.extract[AdminAdjustmentRequest])
        catch case NonFatal(_) => Left(invalid)
      case _ => Left(invalid)

  private def parseAdjustsTransactionId(
      raw: Option[String]
  ): Either[BankingError, Option[UUID]] =
    raw match
      case None => Right(None)
      case Some(value) =>
        Try(UUID.fromString(value)).toOption
          .filter(_.toString == value.toLowerCase)
          .map(Some(_))
          .toRight(BankingError.InvalidRequest("adjustsTransactionId must be a UUID"))

  private def toAdjustmentResponse(adjustment: BalanceAdjustment): AdminAdjustmentResponse =
    AdminAdjustmentResponse(
      adjustmentId = adjustment.adjustmentId.value.toString,
      accountId = adjustment.accountId.value,
      direction = adjustment.direction match
        case AdjustmentDirection.Credit => "credit"
        case AdjustmentDirection.Debit  => "debit",
      amount = adjustment.amount.amount,
      currency = Currency.THB.code,
      reason = adjustment.memo.reason.value,
      actor = adjustment.memo.actor.value,
      adjustsTransactionId = adjustment.adjustsTransactionId.map(_.toString),
      balanceAfter = adjustment.balanceAfter,
      occurredAt = adjustment.occurredAt.toString
    )

  private def toSummaryResponse(overview: AccountOverview): AdminAccountSummaryResponse =
    AdminAccountSummaryResponse(
      accountId = overview.account.id.value,
      currency = Currency.THB.code,
      status = "active",
      balance = overview.account.balance,
      activityCount = overview.activities.size,
      lastActivityAt = overview.activities.lastOption.map(_.occurredAt.toString)
    )

  private def toDetailResponse(overview: AccountOverview): AdminAccountDetailResponse =
    val summary = toSummaryResponse(overview)
    AdminAccountDetailResponse(
      accountId = summary.accountId,
      currency = summary.currency,
      status = summary.status,
      balance = summary.balance,
      activityCount = summary.activityCount,
      lastActivityAt = summary.lastActivityAt,
      activities = overview.activities.map(AccountResponseMapping.toActivityResponse)
    )

  private def errorStatus(error: DomainError): Int =
    error match
      case BankingError.AccountNotFound(_) |
          BankingError.AdjustedTransactionNotFound(_) => 404
      case BankingError.InsufficientFunds(_, _) |
          LedgerError.DuplicateTransaction(_) => 409
      case _ => 400
