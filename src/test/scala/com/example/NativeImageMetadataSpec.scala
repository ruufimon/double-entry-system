package com.example

import scala.io.Source
import scala.util.Using

import org.json4s.*
import org.json4s.jackson.JsonMethods.parse
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class NativeImageMetadataSpec extends AnyFunSuite with Matchers:
  private given org.json4s.Formats = DefaultFormats

  test("native image exposes every account response field to JSON serialization") {
    val resourceName =
      "META-INF/native-image/com.example/scalatra-ping-api/reachability-metadata.json"
    val metadata = Using.resource(
      Option(getClass.getClassLoader.getResourceAsStream(resourceName))
        .getOrElse(fail(s"Missing native-image metadata resource: $resourceName"))
    )(stream => parse(Source.fromInputStream(stream).mkString))

    val expectedFields = Map(
      "com.example.banking.http.AccountActivityResponse" -> Set(
        "transactionId",
        "operation",
        "effect",
        "amount",
        "currency",
        "balanceAfter",
        "occurredAt",
        "status",
        "originalTransactionId",
        "counterpartyAccountId"
      ),
      "com.example.banking.http.AccountOverviewResponse" -> Set(
        "accountId",
        "currency",
        "balance",
        "activities"
      ),
      "com.example.banking.http.TransferResponse" -> Set(
        "transferId",
        "sourceAccountId",
        "destinationAccountId",
        "amount",
        "currency",
        "sourceBalance",
        "occurredAt"
      ),
      "com.example.banking.http.AdminAccountSummaryResponse" -> Set(
        "accountId",
        "currency",
        "status",
        "balance",
        "activityCount",
        "lastActivityAt"
      ),
      "com.example.banking.http.AdminAccountDetailResponse" -> Set(
        "accountId",
        "currency",
        "status",
        "balance",
        "activityCount",
        "lastActivityAt",
        "activities"
      )
    )

    expectedFields.foreach { case (responseType, expected) =>
      val response = (metadata \ "reflection").children.find { entry =>
        (entry \ "type").extractOpt[String].contains(responseType)
      }.getOrElse(fail(s"$responseType is missing from native-image metadata"))
      val reflectedFields = (response \ "fields").children.flatMap { field =>
        (field \ "name").extractOpt[String]
      }.toSet

      reflectedFields shouldBe expected
    }
  }
