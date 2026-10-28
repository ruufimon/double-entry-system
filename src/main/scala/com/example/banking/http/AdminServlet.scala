package com.example.banking.http

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

import scala.util.control.NonFatal

import com.example.banking.application.InterestRateService
import com.example.banking.domain.*
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

final class AdminServlet(
    accountOperations: AccountOperations,
    interestRateService: Option[InterestRateService] = None,
    adminPassword: Option[String] = None
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

  private def withInterestRateAdmin(operation: => Any): Any =
    if interestRateService.isEmpty then
      status = 404
      ErrorResponse("not_found", "Interest settings are unavailable")
    else
      adminPassword.filter(_.nonEmpty) match
        case None =>
          status = 503
          ErrorResponse("admin_auth_not_configured", "Admin password is not configured")
        case Some(expectedPassword) if !isAuthorized(expectedPassword) =>
          response.setHeader("WWW-Authenticate", "Basic realm=\"Ledger Admin\", charset=\"UTF-8\"")
          response.setHeader("Cache-Control", "no-store")
          status = 401
          ErrorResponse("admin_auth_required", "A valid admin password is required")
        case Some(_) =>
          response.setHeader("Cache-Control", "no-store")
          operation

  private def isAuthorized(expectedPassword: String): Boolean =
    Option(request.getHeader("Authorization")).exists { header =>
      val prefix = "Basic "
      if !header.startsWith(prefix) then false
      else
        try
          val decoded = new String(Base64.getDecoder.decode(header.substring(prefix.length)), StandardCharsets.UTF_8)
          val separator = decoded.indexOf(':')
          separator > 0 && decoded.substring(0, separator) == "admin" &&
            MessageDigest.isEqual(
              expectedPassword.getBytes(StandardCharsets.UTF_8),
              decoded.substring(separator + 1).getBytes(StandardCharsets.UTF_8)
            )
        catch case NonFatal(_) => false
    }

  private def parseInterestRateRequest(): Either[BankingError, AdminInterestRateRequest] =
    try Right(parsedBody.extract[AdminInterestRateRequest])
    catch
      case NonFatal(_) =>
        Left(BankingError.InvalidRequest("Request body must contain a numeric rate"))

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
      case BankingError.AccountNotFound(_) => 404
      case _: InterestError                 => 400
      case _                               => 400
