package com.example.banking.http

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.{Base64, UUID}

import scala.util.Try
import scala.util.control.NonFatal

import com.example.banking.application.{AdjustmentCommand, AdjustmentService, InterestRateService}
import com.example.banking.domain.*
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

final case class AdminInterestRateRequest(rate: BigDecimal)
final case class AdminInterestRateHistoryResponse(
    rate: BigDecimal,
    effectiveFrom: String,
    createdAt: String,
    updatedBy: String,
    status: String
)
final case class AdminInterestRateResponse(
    currentRate: BigDecimal,
    currentEffectiveFrom: String,
    nextEffectiveFrom: String,
    scheduledRate: Option[BigDecimal],
    scheduledEffectiveFrom: Option[String],
    history: Vector[AdminInterestRateHistoryResponse]
)

/** Admin console API. Account reads are open; adjustments require `adminToken`;
  * interest rate settings require `adminPassword`.
  */
final class AdminServlet(
    accountOperations: AccountOperations,
    adjustmentService: AdjustmentService,
    interestRateService: Option[InterestRateService],
    adminToken: Option[String],
    adminPassword: Option[String]
)
    extends ScalatraServlet
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

  get("/interest-rate") {
    withInterestRateAdmin {
      interestRateService.map(service => toInterestRateResponse(service.overview))
    }
  }

  put("/interest-rate") {
    withInterestRateAdmin {
      val result = for
        service <- interestRateService.toRight(BankingError.InvalidRequest("Interest settings are unavailable"))
        request <- parseInterestRateRequest()
        overview <- service.setRate(request.rate, "admin")
      yield toInterestRateResponse(overview)

      result match
        case Right(response) =>
          status = 200
          response
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

  private def withInterestRateAdmin(action: => Any): Any =
    val credentials = Option(request.getHeader("Authorization"))
      .collect { case header if header.startsWith("Basic ") => header.stripPrefix("Basic ") }
      .flatMap { encoded =>
        try
          val decoded = new String(Base64.getDecoder.decode(encoded), StandardCharsets.UTF_8)
          decoded.split(":", 2) match
            case Array("admin", password) => Some(password)
            case _ => None
        catch case _: Exception => None
      }

    val authorized = (adminPassword, credentials) match
      case (Some(expected), Some(actual)) =>
        MessageDigest.isEqual(
          expected.getBytes(StandardCharsets.UTF_8),
          actual.getBytes(StandardCharsets.UTF_8)
        )
      case _ => false

    if authorized then action
    else
      response.setHeader("WWW-Authenticate", "Basic realm=\"Admin\"")
      halt(401, ErrorResponse("unauthorized", "Admin authentication required"))

  private def parseAdjustmentRequest(): Either[BankingError, AdminAdjustmentRequest] =
    val invalid = BankingError.InvalidRequest(
      "Request body must be a JSON object with direction, amount, and reason"
    )
    parsedBody match
      case json: JObject =>
        try Right(json.extract[AdminAdjustmentRequest])
        catch case NonFatal(_) => Left(invalid)
      case _ => Left(invalid)

  private def parseAdjustsTransactionId(raw: Option[String]): Either[BankingError, Option[UUID]] =
    raw match
      case None => Right(None)
      case Some(value) =>
        Try(UUID.fromString(value)).toEither match
          case Right(uuid) => Right(Some(uuid))
          case Left(_) => Left(BankingError.InvalidRequest(s"Invalid UUID: $value"))

  private def parseInterestRateRequest(): Either[BankingError, AdminInterestRateRequest] =
    val invalid = BankingError.InvalidRequest(
      "Request body must be a JSON object with rate (a decimal percentage)"
    )
    parsedBody match
      case json: JObject =>
        try Right(json.extract[AdminInterestRateRequest])
        catch case NonFatal(_) => Left(invalid)
      case _ => Left(invalid)

  private def toSummaryResponse(overview: AccountOverview): AdminAccountSummaryResponse =
    AdminAccountSummaryResponse(
      accountId = overview.account.id.value,
      currency = "THB",
      status = "open",
      balance = overview.account.balance,
      activityCount = overview.activities.size,
      lastActivityAt = overview.activities.headOption.map(_.occurredAt.toString)
    )

  private def toDetailResponse(overview: AccountOverview): AdminAccountDetailResponse =
    AdminAccountDetailResponse(
      accountId = overview.account.id.value,
      currency = "THB",
      status = "open",
      balance = overview.account.balance,
      activityCount = overview.activities.size,
      lastActivityAt = overview.activities.headOption.map(_.occurredAt.toString),
      activities = overview.activities.map(AccountResponseMapping.toActivityResponse)
    )

  private def toAdjustmentResponse(adjustment: BalanceAdjustment): AdminAdjustmentResponse =
    AdminAdjustmentResponse(
      adjustmentId = adjustment.adjustmentId.value.toString,
      accountId = adjustment.accountId.value,
      direction = adjustment.direction.toString.toLowerCase,
      amount = adjustment.amount.amount,
      currency = "THB",
      reason = adjustment.memo.reason.value,
      actor = adjustment.memo.actor.value,
      adjustsTransactionId = adjustment.adjustsTransactionId.map(_.toString),
      balanceAfter = adjustment.balanceAfter,
      occurredAt = adjustment.occurredAt.toString
    )

  private def toInterestRateResponse(overview: InterestRateOverview): AdminInterestRateResponse =
    AdminInterestRateResponse(
      currentRate = overview.currentRate.percent,
      currentEffectiveFrom = overview.currentEffectiveFrom.toString,
      nextEffectiveFrom = overview.nextEffectiveFrom.toString,
      scheduledRate = overview.scheduledRate.map(_.percent),
      scheduledEffectiveFrom = overview.scheduledEffectiveFrom.map(_.toString),
      history = overview.history.map { item =>
        AdminInterestRateHistoryResponse(
          rate = item.rate.percent,
          effectiveFrom = item.effectiveFrom.toString,
          createdAt = item.createdAt.toString,
          updatedBy = item.updatedBy,
          status = item.status.toString.toLowerCase
        )
      }
    )

  private def errorStatus(error: DomainError): Int = error match
    case _: BankingError.AccountNotFound => 404
    case _: BankingError.InsufficientFunds => 422
    case _: BankingError.InvalidRequest => 400
    case _: LedgerError => 422
