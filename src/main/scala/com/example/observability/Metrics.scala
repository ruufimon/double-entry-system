package com.example.observability

import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit

import scala.util.control.NonFatal

import io.micrometer.core.instrument.{MeterRegistry, Timer}
import io.micrometer.prometheusmetrics.{PrometheusConfig, PrometheusMeterRegistry}
import jakarta.servlet.{Filter, FilterChain, ServletRequest, ServletResponse}
import jakarta.servlet.http.{HttpServlet, HttpServletRequest, HttpServletResponse}

object Metrics:
  /** Latency buckets from spec/MonitoringSLO.md. */
  val RequestBuckets: Array[Duration] = Array(
    Duration.ofMillis(25),
    Duration.ofMillis(50),
    Duration.ofMillis(100),
    Duration.ofMillis(200),
    Duration.ofMillis(500),
    Duration.ofSeconds(1),
    Duration.ofSeconds(2),
    Duration.ofSeconds(5)
  )

  val RequestTimerName = "http.server.requests"

  /** Methods recorded by name. HTTP allows any token as a method, so everything else is `OTHER`;
    * otherwise a client could create a new histogram per request.
    */
  val KnownMethods: Set[String] = Set("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")

  def methodLabel(method: String): String =
    val upper = Option(method).fold("")(_.toUpperCase)
    if KnownMethods.contains(upper) then upper else "OTHER"

  def registry(): PrometheusMeterRegistry =
    val registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    registry.config().commonTags("service", "banking-api")
    registry

/** Times every request on the application connector as `http_server_requests_seconds`.
  *
  * Labels are the documented route template, method, status, outcome and class; see
  * [[RouteTemplate]] and [[Outcome]]. Recording happens in `finally`, so an exception that escapes
  * the servlet still counts, as a 500.
  */
final class RequestMetricsFilter(registry: MeterRegistry) extends Filter:
  override def doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain): Unit =
    (request, response) match
      case (httpRequest: HttpServletRequest, httpResponse: HttpServletResponse) =>
        val started = System.nanoTime()
        var status = 500
        try
          chain.doFilter(request, response)
          status = httpResponse.getStatus
        finally
          record(httpRequest, status, System.nanoTime() - started)
      case _ =>
        chain.doFilter(request, response)

  private def record(request: HttpServletRequest, status: Int, elapsedNanos: Long): Unit =
    try
      val route = RouteTemplate.of(request.getMethod, request.getRequestURI)
      Timer
        .builder(Metrics.RequestTimerName)
        .description("HTTP request latency by documented route")
        .tag("route", route.template)
        .tag("method", Metrics.methodLabel(request.getMethod))
        .tag("status", status.toString)
        .tag("outcome", Outcome.of(route.routeClass, status))
        .tag("class", route.routeClass.label)
        .serviceLevelObjectives(Metrics.RequestBuckets*)
        .register(registry)
        .record(elapsedNanos, TimeUnit.NANOSECONDS)
    catch case NonFatal(_) => () // metrics must never fail a request

/** Serves the Prometheus text exposition. Mounted only on the metrics connector. */
final class MetricsServlet(registry: PrometheusMeterRegistry) extends HttpServlet:
  @throws[IOException]
  override def doGet(request: HttpServletRequest, response: HttpServletResponse): Unit =
    response.setStatus(200)
    response.setContentType("text/plain; version=0.0.4; charset=utf-8")
    response.getWriter.write(registry.scrape())
