package com.example.observability

import scala.util.matching.Regex

/** The endpoint class a route belongs to, as defined in spec/MonitoringSLO.md. */
enum RouteClass(val label: String):
  case Read extends RouteClass("read")
  case Money extends RouteClass("money")
  case Bill extends RouteClass("bill")
  case Probe extends RouteClass("probe")
  case Other extends RouteClass("other")

final case class RouteMatch(template: String, routeClass: RouteClass)

/** Maps a request to its documented route template so metric labels stay bounded.
  *
  * The table mirrors the API table in the README. Anything else is `unmatched`, so a scan of random
  * paths cannot create label values. Account, inquiry and payment identifiers never appear.
  */
object RouteTemplate:
  val Unmatched: RouteMatch = RouteMatch("unmatched", RouteClass.Other)

  private val Segment = "[^/]+"

  private final case class Entry(method: String, pattern: Regex, template: String, routeClass: RouteClass)

  private def entry(method: String, template: String, routeClass: RouteClass): Entry =
    val pattern = template
      .split("/")
      .map(part => if part.startsWith(":") then Segment else Regex.quote(part))
      .mkString("^", "/", "$")
    Entry(method, pattern.r, template, routeClass)

  private val Entries: Vector[Entry] = Vector(
    entry("GET", "/ping", RouteClass.Probe),
    entry("GET", "/metrics", RouteClass.Probe),
    entry("PUT", "/accounts/:accountId", RouteClass.Money),
    entry("GET", "/accounts/:accountId/overview", RouteClass.Read),
    entry("GET", "/accounts/:accountId/balance", RouteClass.Read),
    entry("GET", "/accounts/:accountId/activities", RouteClass.Read),
    entry("POST", "/accounts/:accountId/deposits", RouteClass.Money),
    entry("POST", "/accounts/:accountId/withdrawals", RouteClass.Money),
    entry("POST", "/accounts/:accountId/transfers", RouteClass.Money),
    entry("POST", "/accounts/:accountId/bill-payments/inquiries", RouteClass.Bill),
    entry("POST", "/accounts/:accountId/bill-payments/:inquiryId/confirm", RouteClass.Bill),
    entry("GET", "/accounts/:accountId/bill-payments/:paymentId", RouteClass.Read),
    entry("GET", "/admin/accounts", RouteClass.Read),
    entry("GET", "/admin/accounts/:accountId", RouteClass.Read),
    entry("POST", "/admin/accounts/:accountId/adjustments", RouteClass.Money),
    entry("GET", "/admin/accounts/:accountId/adjustments", RouteClass.Read),
    entry("GET", "/admin/interest-rate", RouteClass.Read),
    entry("PUT", "/admin/interest-rate", RouteClass.Money)
  )

  /** Every documented template, for tests and documentation. */
  val templates: Vector[(String, String, RouteClass)] =
    Entries.map(e => (e.method, e.template, e.routeClass))

  def of(method: String, path: String): RouteMatch =
    val upper = method.toUpperCase
    Entries
      .find(e => e.method == upper && e.pattern.matches(path))
      .map(e => RouteMatch(e.template, e.routeClass))
      .getOrElse(Unmatched)
