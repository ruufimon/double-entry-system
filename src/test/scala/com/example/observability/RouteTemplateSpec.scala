package com.example.observability

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class RouteTemplateSpec extends AnyFunSuite with Matchers:
  private val id = "acc-123"
  private val uuid = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"

  private val expectations: Seq[(String, String, String, RouteClass)] = Seq(
    ("GET", "/ping", "/ping", RouteClass.Probe),
    ("GET", "/metrics", "/metrics", RouteClass.Probe),
    ("PUT", s"/accounts/$id", "/accounts/:accountId", RouteClass.Money),
    ("GET", s"/accounts/$id/overview", "/accounts/:accountId/overview", RouteClass.Read),
    ("GET", s"/accounts/$id/balance", "/accounts/:accountId/balance", RouteClass.Read),
    ("GET", s"/accounts/$id/activities", "/accounts/:accountId/activities", RouteClass.Read),
    ("POST", s"/accounts/$id/deposits", "/accounts/:accountId/deposits", RouteClass.Money),
    ("POST", s"/accounts/$id/withdrawals", "/accounts/:accountId/withdrawals", RouteClass.Money),
    ("POST", s"/accounts/$id/transfers", "/accounts/:accountId/transfers", RouteClass.Money),
    ("POST", s"/accounts/$id/bill-payments/inquiries", "/accounts/:accountId/bill-payments/inquiries", RouteClass.Bill),
    ("POST", s"/accounts/$id/bill-payments/$uuid/confirm", "/accounts/:accountId/bill-payments/:inquiryId/confirm", RouteClass.Bill),
    ("GET", s"/accounts/$id/bill-payments/$uuid", "/accounts/:accountId/bill-payments/:paymentId", RouteClass.Read),
    ("GET", "/admin/accounts", "/admin/accounts", RouteClass.Read),
    ("GET", s"/admin/accounts/$id", "/admin/accounts/:accountId", RouteClass.Read),
    ("POST", s"/admin/accounts/$id/adjustments", "/admin/accounts/:accountId/adjustments", RouteClass.Money),
    ("GET", s"/admin/accounts/$id/adjustments", "/admin/accounts/:accountId/adjustments", RouteClass.Read),
    ("GET", "/admin/interest-rate", "/admin/interest-rate", RouteClass.Read),
    ("PUT", "/admin/interest-rate", "/admin/interest-rate", RouteClass.Money)
  )

  expectations.foreach { case (method, path, template, routeClass) =>
    test(s"$method $path -> $template (${routeClass.label})") {
      RouteTemplate.of(method, path) shouldBe RouteMatch(template, routeClass)
    }
  }

  test("every documented template is covered by this spec") {
    val covered = expectations.map { case (m, _, t, _) => (m, t) }.toSet
    RouteTemplate.templates.map { case (m, t, _) => (m, t) }.toSet shouldBe covered
  }

  test("method is case-insensitive") {
    RouteTemplate.of("get", "/ping") shouldBe RouteMatch("/ping", RouteClass.Probe)
  }

  test("unknown paths, wrong methods and extra segments are unmatched so labels stay bounded") {
    RouteTemplate.of("GET", "/accounts/x/y/z") shouldBe RouteTemplate.Unmatched
    RouteTemplate.of("DELETE", s"/accounts/$id") shouldBe RouteTemplate.Unmatched
    RouteTemplate.of("GET", s"/accounts/$id") shouldBe RouteTemplate.Unmatched
    RouteTemplate.of("GET", "/accounts//balance") shouldBe RouteTemplate.Unmatched
    RouteTemplate.of("GET", s"/accounts/$id/balance/") shouldBe RouteTemplate.Unmatched
    RouteTemplate.of("GET", "/nope") shouldBe RouteTemplate.Unmatched
  }

  test("identifiers never leak into the template") {
    RouteTemplate.of("GET", "/accounts/secret-account/balance").template should not include "secret"
  }
