package com.example.acceptance

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

import com.example.{BankingApiServer, Server}
import org.json4s.*
import org.json4s.jackson.JsonMethods.parse
import org.scalatest.BeforeAndAfterEach
import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers

/** Acceptance criteria for admin balance adjustments, exercised against the real API.
  *
  * Design: spec/BalanceAdjustment.md
  */
final class BalanceAdjustmentAcceptanceSpec
    extends AnyFeatureSpec
    with GivenWhenThen
    with Matchers
    with BeforeAndAfterEach:
  private implicit val jsonFormats: Formats = DefaultFormats
  private val AdminToken = "acceptance-admin-token"
  private val AdminActor = "ops-alice"
  private val client = HttpClient
    .newBuilder()
    .connectTimeout(Duration.ofSeconds(2))
    .build()
  private var apiServer: BankingApiServer = _

  override protected def beforeEach(): Unit =
    super.beforeEach()
    apiServer = Server.create(0, billerUrl = None, adminToken = Some(AdminToken))
    apiServer.start()

  override protected def afterEach(): Unit =
    try
      if apiServer != null then apiServer.close()
    finally
      apiServer = null
      super.afterEach()

  Feature("Admin corrects a balance after a calculation mistake") {
    Scenario("an under-credited deposit is topped up with a linked credit adjustment") {
      Given("a deposit that was recorded as 100.00 instead of 150.00")
      fundAccount("adjust-credit", "100.00")
      val depositId = onlyTransactionId("adjust-credit", "deposit")

      When("an admin posts a 50.00 credit adjustment referencing the deposit")
      val response = adjust(
        "adjust-credit",
        s"""{"direction":"credit","amount":50.00,"reason":"Deposit keyed as 100 instead of 150","adjustsTransactionId":"$depositId"}"""
      )

      Then("the adjustment is created with its reason, actor, and resulting balance")
      response.statusCode() shouldBe 201
      val adjustment = parse(response.body())
      (adjustment \ "adjustmentId").extract[String] should not be empty
      (adjustment \ "accountId").extract[String] shouldBe "adjust-credit"
      (adjustment \ "direction").extract[String] shouldBe "credit"
      (adjustment \ "amount").extract[BigDecimal] shouldBe BigDecimal("50.00")
      (adjustment \ "currency").extract[String] shouldBe "THB"
      (adjustment \ "reason").extract[String] shouldBe "Deposit keyed as 100 instead of 150"
      (adjustment \ "actor").extract[String] shouldBe AdminActor
      (adjustment \ "adjustsTransactionId").extract[String] shouldBe depositId
      (adjustment \ "balanceAfter").extract[BigDecimal] shouldBe BigDecimal("150.00")

      And("the customer sees the corrected balance and an adjustment activity")
      val overview = customerOverview("adjust-credit")
      (overview \ "balance").extract[BigDecimal] shouldBe BigDecimal("150.00")
      val activities = (overview \ "activities").children
      activities.map(a => (a \ "operation").extract[String]) shouldBe List("deposit", "adjustment")
      val adjustmentActivity = activities.last
      (adjustmentActivity \ "transactionId").extract[String] shouldBe
        (adjustment \ "adjustmentId").extract[String]
      (adjustmentActivity \ "effect").extract[String] shouldBe "increase"
      (adjustmentActivity \ "amount").extract[BigDecimal] shouldBe BigDecimal("50.00")
      (adjustmentActivity \ "balanceAfter").extract[BigDecimal] shouldBe BigDecimal("150.00")
      (adjustmentActivity \ "status").extract[String] shouldBe "posted"
      (adjustmentActivity \ "originalTransactionId").extract[String] shouldBe depositId

      And("the original deposit stays untouched in history")
      val deposit = activities.head
      (deposit \ "amount").extract[BigDecimal] shouldBe BigDecimal("100.00")
      (deposit \ "status").extract[String] shouldBe "posted"
    }

    Scenario("an over-credited deposit is clawed back with a debit adjustment") {
      Given("a deposit that was recorded as 200.00 instead of 120.00")
      fundAccount("adjust-debit", "200.00")
      val depositId = onlyTransactionId("adjust-debit", "deposit")

      When("an admin posts an 80.00 debit adjustment")
      val response = adjust(
        "adjust-debit",
        s"""{"direction":"debit","amount":80.00,"reason":"Deposit over-credited","adjustsTransactionId":"$depositId"}"""
      )

      Then("the balance is reduced to the correct amount")
      response.statusCode() shouldBe 201
      (parse(response.body()) \ "balanceAfter").extract[BigDecimal] shouldBe BigDecimal("120.00")
      val overview = customerOverview("adjust-debit")
      (overview \ "balance").extract[BigDecimal] shouldBe BigDecimal("120.00")
      val adjustmentActivity = (overview \ "activities").children.last
      (adjustmentActivity \ "operation").extract[String] shouldBe "adjustment"
      (adjustmentActivity \ "effect").extract[String] shouldBe "decrease"
    }

    Scenario("an adjustment without a linked transaction is allowed") {
      Given("an account with a balance of 10.00")
      fundAccount("adjust-unlinked", "10.00")

      When("an admin posts a goodwill credit with no linked transaction")
      val response = adjust(
        "adjust-unlinked",
        """{"direction":"credit","amount":5.25,"reason":"Fee charged in error outside the ledger"}"""
      )

      Then("the adjustment is posted without a reference")
      response.statusCode() shouldBe 201
      val adjustment = parse(response.body())
      (adjustment \ "adjustsTransactionId") shouldBe JNothing
      (adjustment \ "balanceAfter").extract[BigDecimal] shouldBe BigDecimal("15.25")
    }

    Scenario("the same transaction can be corrected more than once") {
      Given("a deposit that already received one adjustment")
      fundAccount("adjust-twice", "100.00")
      val depositId = onlyTransactionId("adjust-twice", "deposit")
      adjust(
        "adjust-twice",
        s"""{"direction":"credit","amount":30.00,"reason":"First correction","adjustsTransactionId":"$depositId"}"""
      ).statusCode() shouldBe 201

      When("an admin posts a second adjustment for the same deposit")
      val response = adjust(
        "adjust-twice",
        s"""{"direction":"debit","amount":10.00,"reason":"First correction overshot","adjustsTransactionId":"$depositId"}"""
      )

      Then("both adjustments apply")
      response.statusCode() shouldBe 201
      (customerOverview("adjust-twice") \ "balance").extract[BigDecimal] shouldBe
        BigDecimal("120.00")
    }

    Scenario("the admin console lists adjustments with their audit details") {
      Given("an account with two adjustments")
      fundAccount("adjust-history", "50.00")
      val depositId = onlyTransactionId("adjust-history", "deposit")
      adjust(
        "adjust-history",
        s"""{"direction":"credit","amount":20.00,"reason":"Missed bonus","adjustsTransactionId":"$depositId"}"""
      ).statusCode() shouldBe 201
      adjust(
        "adjust-history",
        """{"direction":"debit","amount":5.00,"reason":"Bonus overstated"}""",
        actor = "ops-bob"
      ).statusCode() shouldBe 201

      When("an admin lists the account's adjustments")
      val response = adminGet("/admin/accounts/adjust-history/adjustments")

      Then("they are returned chronologically with reason and actor")
      response.statusCode() shouldBe 200
      val adjustments = parse(response.body()).children
      adjustments.map(a => (a \ "reason").extract[String]) shouldBe
        List("Missed bonus", "Bonus overstated")
      adjustments.map(a => (a \ "actor").extract[String]) shouldBe List(AdminActor, "ops-bob")
      adjustments.map(a => (a \ "direction").extract[String]) shouldBe List("credit", "debit")
      (adjustments.head \ "adjustsTransactionId").extract[String] shouldBe depositId

      And("the admin account detail reflects the adjusted balance")
      val detail = parse(adminGet("/admin/accounts/adjust-history").body())
      (detail \ "balance").extract[BigDecimal] shouldBe BigDecimal("65.00")
      (detail \ "activityCount").extract[Int] shouldBe 3
    }
  }

  Feature("Adjustments never break ledger rules") {
    Scenario("a debit adjustment larger than the balance is rejected") {
      Given("an account with a balance of 30.00")
      fundAccount("adjust-overdraw", "30.00")

      When("an admin tries to debit 30.01")
      val response = adjust(
        "adjust-overdraw",
        """{"direction":"debit","amount":30.01,"reason":"Claw back"}"""
      )

      Then("it is rejected and the balance is unchanged")
      response.statusCode() shouldBe 409
      errorCode(response) shouldBe "insufficient_funds"
      assertUnchanged("adjust-overdraw", "30.00")
    }

    Scenario("a replayed idempotency key does not post twice") {
      Given("an adjustment that was already posted with an idempotency key")
      fundAccount("adjust-replay", "10.00")
      val key = UUID.randomUUID().toString
      val body = """{"direction":"credit","amount":1.00,"reason":"Rounding fix"}"""
      adjust("adjust-replay", body, idempotencyKey = key).statusCode() shouldBe 201

      When("the same request is sent again with the same key")
      val replay = adjust("adjust-replay", body, idempotencyKey = key)

      Then("it is rejected as a duplicate and the balance moved only once")
      replay.statusCode() shouldBe 409
      errorCode(replay) shouldBe "duplicate_ledger_transaction"
      (customerOverview("adjust-replay") \ "balance").extract[BigDecimal] shouldBe
        BigDecimal("11.00")
    }

    Scenario("a linked transaction must exist") {
      fundAccount("adjust-missing-link", "10.00")

      val response = adjust(
        "adjust-missing-link",
        s"""{"direction":"credit","amount":1.00,"reason":"Fix","adjustsTransactionId":"${UUID.randomUUID()}"}"""
      )

      response.statusCode() shouldBe 404
      errorCode(response) shouldBe "adjusted_transaction_not_found"
      assertUnchanged("adjust-missing-link", "10.00")
    }

    Scenario("a linked transaction must belong to the adjusted account") {
      Given("a deposit on another account")
      fundAccount("adjust-other-owner", "10.00")
      val foreignDepositId = onlyTransactionId("adjust-other-owner", "deposit")
      fundAccount("adjust-mismatch", "10.00")

      When("an admin links it to an adjustment on this account")
      val response = adjust(
        "adjust-mismatch",
        s"""{"direction":"credit","amount":1.00,"reason":"Fix","adjustsTransactionId":"$foreignDepositId"}"""
      )

      Then("it is rejected")
      response.statusCode() shouldBe 400
      errorCode(response) shouldBe "adjusted_transaction_account_mismatch"
      assertUnchanged("adjust-mismatch", "10.00")
    }

    Scenario("an unknown account cannot be adjusted") {
      val response = adjust(
        "adjust-nobody",
        """{"direction":"credit","amount":1.00,"reason":"Fix"}"""
      )

      response.statusCode() shouldBe 404
      errorCode(response) shouldBe "account_not_found"
    }

    Scenario("invalid adjustment requests are rejected without moving money") {
      fundAccount("adjust-invalid", "10.00")
      val cases = Vector(
        """{"direction":"credit","amount":0,"reason":"Fix"}""" -> "invalid_adjustment_amount",
        """{"direction":"credit","amount":-1.00,"reason":"Fix"}""" -> "invalid_adjustment_amount",
        """{"direction":"credit","amount":1.001,"reason":"Fix"}""" -> "invalid_adjustment_amount",
        """{"direction":"sideways","amount":1.00,"reason":"Fix"}""" -> "invalid_adjustment_direction",
        """{"direction":"credit","amount":1.00,"reason":"   "}""" -> "invalid_adjustment_reason",
        """{"direction":"credit","amount":1.00}""" -> "invalid_adjustment_reason",
        s"""{"direction":"credit","amount":1.00,"reason":"${"x" * 501}"}""" ->
          "invalid_adjustment_reason",
        """{"direction":"credit","amount":1.00,"reason":"Fix","adjustsTransactionId":"not-a-uuid"}""" ->
          "invalid_request",
        "not json" -> "invalid_request"
      )

      cases.foreach { case (body, expectedCode) =>
        val response = adjust("adjust-invalid", body)
        withClue(s"body=$body: ") {
          response.statusCode() shouldBe 400
          errorCode(response) shouldBe expectedCode
        }
      }
      assertUnchanged("adjust-invalid", "10.00")
    }
  }

  Feature("Only authenticated admins can adjust balances") {
    Scenario("a request without a valid admin token is rejected") {
      fundAccount("adjust-auth", "10.00")
      val body = """{"direction":"credit","amount":1.00,"reason":"Fix"}"""

      val missing = adjust("adjust-auth", body, token = None)
      val wrong = adjust("adjust-auth", body, token = Some("not-the-token"))

      missing.statusCode() shouldBe 401
      errorCode(missing) shouldBe "unauthorized"
      wrong.statusCode() shouldBe 401
      errorCode(wrong) shouldBe "unauthorized"
      adminGet("/admin/accounts/adjust-auth/adjustments", token = None).statusCode() shouldBe 401
      assertUnchanged("adjust-auth", "10.00")
    }

    Scenario("the acting admin and idempotency key are required") {
      fundAccount("adjust-headers", "10.00")
      val body = """{"direction":"credit","amount":1.00,"reason":"Fix"}"""

      val noActor = adjust("adjust-headers", body, actor = "")
      noActor.statusCode() shouldBe 400
      errorCode(noActor) shouldBe "missing_admin_actor"

      val noKey = adjust("adjust-headers", body, idempotencyKey = "")
      noKey.statusCode() shouldBe 400
      errorCode(noKey) shouldBe "invalid_idempotency_key"

      val badKey = adjust("adjust-headers", body, idempotencyKey = "not-a-uuid")
      badKey.statusCode() shouldBe 400
      errorCode(badKey) shouldBe "invalid_idempotency_key"

      assertUnchanged("adjust-headers", "10.00")
    }

    Scenario("adjustments are disabled when no admin token is configured") {
      apiServer.close()
      apiServer = Server.create(0, billerUrl = None, adminToken = None)
      apiServer.start()
      fundAccount("adjust-disabled", "10.00")

      val response = adjust(
        "adjust-disabled",
        """{"direction":"credit","amount":1.00,"reason":"Fix"}"""
      )

      response.statusCode() shouldBe 401
      errorCode(response) shouldBe "unauthorized"
      assertUnchanged("adjust-disabled", "10.00")
    }
  }

  private def fundAccount(accountId: String, amount: String): Unit =
    send(HttpRequest.newBuilder(uri(s"/accounts/$accountId")).PUT(BodyPublishers.ofString("{}")))
      .statusCode() shouldBe 201
    send(
      HttpRequest
        .newBuilder(uri(s"/accounts/$accountId/deposits"))
        .POST(BodyPublishers.ofString(s"""{"amount":$amount}"""))
    ).statusCode() shouldBe 200

  private def onlyTransactionId(accountId: String, operation: String): String =
    val matching = (customerOverview(accountId) \ "activities").children.filter { activity =>
      (activity \ "operation").extract[String] == operation
    }
    matching should have size 1
    (matching.head \ "transactionId").extract[String]

  private def customerOverview(accountId: String): JValue =
    val response = send(HttpRequest.newBuilder(uri(s"/accounts/$accountId/overview")).GET())
    response.statusCode() shouldBe 200
    parse(response.body())

  private def assertUnchanged(accountId: String, expectedBalance: String): Unit =
    val overview = customerOverview(accountId)
    (overview \ "balance").extract[BigDecimal] shouldBe BigDecimal(expectedBalance)
    (overview \ "activities").children.map(a => (a \ "operation").extract[String]) shouldBe
      List("deposit")

  private def adjust(
      accountId: String,
      body: String,
      token: Option[String] = Some(AdminToken),
      actor: String = AdminActor,
      idempotencyKey: String = UUID.randomUUID().toString
  ): HttpResponse[String] =
    val builder = HttpRequest
      .newBuilder(uri(s"/admin/accounts/$accountId/adjustments"))
      .POST(BodyPublishers.ofString(body))
    token.foreach(value => builder.header("Authorization", s"Bearer $value"))
    if actor.nonEmpty then builder.header("X-Admin-Actor", actor)
    if idempotencyKey.nonEmpty then builder.header("Idempotency-Key", idempotencyKey)
    send(builder)

  private def adminGet(
      path: String,
      token: Option[String] = Some(AdminToken)
  ): HttpResponse[String] =
    val builder = HttpRequest.newBuilder(uri(path)).GET()
    token.foreach(value => builder.header("Authorization", s"Bearer $value"))
    send(builder)

  private def errorCode(response: HttpResponse[String]): String =
    (parse(response.body()) \ "error").extract[String]

  private def send(builder: HttpRequest.Builder): HttpResponse[String] =
    client.send(
      builder
        .timeout(Duration.ofSeconds(3))
        .header("Content-Type", "application/json")
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )

  private def uri(path: String): URI =
    URI.create(s"http://127.0.0.1:${apiServer.port}$path")
