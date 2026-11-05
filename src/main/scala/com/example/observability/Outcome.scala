package com.example.observability

/** Classifies a response for the availability SLI, per the error table in spec/MonitoringSLO.md.
  *
  * Decided from status and route class only, so no response body is parsed. `success` and
  * `rejected` are good events; the other three count against availability.
  */
object Outcome:
  val Success = "success"
  val Rejected = "rejected"
  val Dependency = "dependency"
  val Misconfigured = "misconfigured"
  val Error = "error"

  def of(routeClass: RouteClass, status: Int): String =
    if status < 400 then Success
    else if status < 500 then Rejected
    else
      (routeClass, status) match
        case (RouteClass.Bill, 502 | 503) => Dependency
        case (_, 503)                     => Misconfigured
        case _                            => Error

  def isGood(outcome: String): Boolean =
    outcome == Success || outcome == Rejected
