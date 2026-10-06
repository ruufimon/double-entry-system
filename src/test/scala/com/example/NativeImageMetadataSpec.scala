package com.example

import scala.io.Source
import scala.util.Using

import org.json4s.*
import org.json4s.jackson.JsonMethods.parse
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class NativeImageMetadataSpec extends AnyFunSuite with Matchers:
  private given org.json4s.Formats = DefaultFormats

  test("native image exposes every account overview response field to JSON serialization") {
    val resourceName =
      "META-INF/native-image/com.example/scalatra-ping-api/reachability-metadata.json"
    val metadata = Using.resource(
      Option(getClass.getClassLoader.getResourceAsStream(resourceName))
        .getOrElse(fail(s"Missing native-image metadata resource: $resourceName"))
    )(stream => parse(Source.fromInputStream(stream).mkString))

    val overview = (metadata \ "reflection").children.find { entry =>
      (entry \ "type").extractOpt[String].contains(
        "com.example.banking.http.AccountOverviewResponse"
      )
    }.getOrElse(fail("AccountOverviewResponse is missing from native-image metadata"))

    val reflectedFields = (overview \ "fields").children.flatMap { field =>
      (field \ "name").extractOpt[String]
    }.toSet

    reflectedFields shouldBe Set("accountId", "currency", "balance", "activities")
  }
