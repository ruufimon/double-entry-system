package com.example.observability

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.http.HttpRequest.BodyPublishers
import java.time.{Clock, Duration, ZoneId}

import com.example.{BankingApiServer, Server}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Scrapes the real server: the money-class SLI must be observable end to end. */
final class MetricsEndpointSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll:
  private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
  private var server: BankingApiServer = _

  override protected def beforeAll(): Unit =
    super.beforeAll()
    server = Server.create(
      0,
      billerUrl = None,
      adminToken = None,
      adminPassword = None,
      clock = Clock.systemUTC(),
      businessZone = ZoneId.of("Asia/Bangkok"),
      metricsPort = 0
    )
    server.start()

  override protected def afterAll(): Unit =
    try if server != null then server.close()
    finally super.afterAll()

  private def send(port: Int, builder: HttpRequest.Builder): HttpResponse[String] =
    client.send(
      builder.timeout(Duration.ofSeconds(3)).header("Content-Type", "application/json").build(),
      HttpResponse.BodyHandlers.ofString()
    )

  private def app(method: String, path: String, body: String = ""): HttpResponse[String] =
    val builder = HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:${server.port}$path"))
    send(server.port, builder.method(method, BodyPublishers.ofString(body)))

  private def scrape(): String =
    val response = send(
      server.metricsPort,
      HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:${server.metricsPort}/metrics")).GET()
    )
    response.statusCode() shouldBe 200
    response.headers().firstValue("Content-Type").orElse("") should startWith("text/plain")
    response.body()

  private def countLine(body: String, labels: String*): Option[Double] =
    body.linesIterator
      .filter(_.startsWith("http_server_requests_seconds_count{"))
      .find(line => labels.forall(line.contains))
      .map(_.split(" ").last.toDouble)

  test("money, rejected and unmatched requests appear with bounded labels") {
    app("PUT", "/accounts/metrics-spec", "{}").statusCode() shouldBe 201
    app("POST", "/accounts/metrics-spec/deposits", """{"amount":10.00}""").statusCode() shouldBe 200
    app("POST", "/accounts/metrics-spec/withdrawals", """{"amount":999.00}""").statusCode() shouldBe 409
    app("GET", "/accounts/metrics-spec/balance").statusCode() shouldBe 200
    app("GET", "/accounts/metrics-spec/no/such/route").statusCode() shouldBe 404

    val body = scrape()
    countLine(body, """route="/accounts/:accountId/deposits"""", """class="money"""", """outcome="success"""", """status="200"""") shouldBe Some(1.0)
    countLine(body, """route="/accounts/:accountId"""", """method="PUT"""", """outcome="success"""", """status="201"""") shouldBe Some(1.0)
    countLine(body, """route="/accounts/:accountId/withdrawals"""", """outcome="rejected"""", """status="409"""") shouldBe Some(1.0)
    countLine(body, """route="/accounts/:accountId/balance"""", """class="read"""") shouldBe Some(1.0)
    countLine(body, """route="unmatched"""", """class="other"""", """outcome="rejected"""", """status="404"""") shouldBe Some(1.0)
    body should not include "metrics-spec"
    body should include("""http_server_requests_seconds_bucket{""")
    body should include("""le="0.5"""")
    body should include("""service="banking-api"""")
  }

  test("pool and ledger metrics are exposed") {
    val body = scrape()
    body should include("""hikaricp_connections_max{""")
    body should include("""pool="banking"""")
    body should include("banking_ledger_unbalanced_transactions{")
  }

  test("/metrics is not served on the application port and routes are not served on the metrics port") {
    app("GET", "/metrics").statusCode() shouldBe 404
    val onMetricsPort = send(
      server.metricsPort,
      HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:${server.metricsPort}/accounts/metrics-spec/balance")).GET()
    )
    onMetricsPort.statusCode() shouldBe 404
    // The app-port 404 is recorded as a probe; scrapes on the metrics port are never recorded.
    val body = scrape()
    countLine(body, """route="/metrics"""", """class="probe"""", """status="404"""") shouldBe Some(1.0)
    countLine(body, """route="/metrics"""", """status="200"""") shouldBe None
  }
