package com.example.banking.http

import com.example.banking.domain.{AccountId, AccountOverview, BankingError, Currency}
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

final class AdminServlet(accountOperations: AccountOperations)
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
      case _                               => 400
