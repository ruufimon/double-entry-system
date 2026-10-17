package com.example.billpayment.infrastructure

import java.io.IOException
import java.net.{URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse, HttpTimeoutException}
import java.net.http.HttpRequest.BodyPublishers
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}

import scala.util.Try

import com.example.billpayment.domain.{
  BillerCode,
  BillerDebt,
  BillerReceipt,
  BillerReference,
  BillPaymentAmount,
  BillPaymentError,
  BillPaymentId
}
import com.example.billpayment.ports.BillerGateway
import org.json4s.*
import org.json4s.jackson.JsonMethods.{compact, parse, render}

/** Talks to an external biller over HTTP, such as the fake biller in `com.example.biller`. */
final class HttpBillerGateway(baseUrl: String, requestTimeout: Duration) extends BillerGateway:
  private val client = HttpClient
    .newBuilder()
    .connectTimeout(requestTimeout)
    .build()
  private val normalizedBaseUrl = baseUrl.stripSuffix("/")

  override def fetchDebt(
      billerCode: BillerCode,
      referenceCode1: BillerReference,
      referenceCode2: BillerReference
  ): Either[BillPaymentError, BillerDebt] =
    val uri = URI.create(
      s"$normalizedBaseUrl/billers/${encode(billerCode.value)}/bills" +
        s"?referenceCode1=${encode(referenceCode1.value)}" +
        s"&referenceCode2=${encode(referenceCode2.value)}"
    )
    send(HttpRequest.newBuilder(uri).GET())
      .left.map(BillPaymentError.BillerUnavailable.apply)
      .flatMap { response =>
        response.statusCode() match
          case 200 =>
            decimalField(response.body(), "amount")
              .toRight(BillPaymentError.BillerUnavailable("Biller returned an unreadable debt"))
              .flatMap(BillPaymentAmount.from)
              .map(amount => BillerDebt(billerCode, referenceCode1, referenceCode2, amount))
          case 404 if errorCode(response.body()).contains("biller_not_found") =>
            Left(BillPaymentError.BillerNotFound(billerCode.value))
          case 404 => Left(BillPaymentError.BillNotFound)
          case 409 => Left(BillPaymentError.BillNotPayable)
          case other =>
            Left(BillPaymentError.BillerUnavailable(s"Biller responded with HTTP $other"))
      }

  override def settle(
      debt: BillerDebt,
      paymentId: BillPaymentId,
      paidAt: Instant
  ): Either[BillPaymentError, BillerReceipt] =
    val uri = URI.create(s"$normalizedBaseUrl/billers/${encode(debt.billerCode.value)}/payments")
    val requestBody = compact(
      render(
        JObject(
          "paymentId" -> JString(paymentId.value.toString),
          "referenceCode1" -> JString(debt.referenceCode1.value),
          "referenceCode2" -> JString(debt.referenceCode2.value),
          "amount" -> JDecimal(debt.amount.amount),
          "paidAt" -> JString(paidAt.toString)
        )
      )
    )
    val request = HttpRequest
      .newBuilder(uri)
      .header("Content-Type", "application/json")
      .POST(BodyPublishers.ofString(requestBody))

    send(request)
      .left.map(details => BillPaymentError.SettlementFailed(s"Biller unreachable: $details"))
      .flatMap { response =>
        response.statusCode() match
          case 200 =>
            val json = parseJson(response.body())
            val receipt = for
              receiptCode <- json.map(_ \ "receiptCode").collect { case JString(code) => code }
              receiptPaidAt <- json.map(_ \ "paidAt").collect { case JString(raw) => raw }
                .flatMap(raw => Try(Instant.parse(raw)).toOption)
            yield BillerReceipt(receiptCode, receiptPaidAt)
            receipt.toRight(
              BillPaymentError.SettlementFailed("Biller returned an unreadable receipt")
            )
          case 404 => Left(BillPaymentError.BillNotFound)
          case 409 => Left(BillPaymentError.BillNotPayable)
          case other =>
            val reason = errorCode(response.body()).fold("")(code => s": $code")
            Left(BillPaymentError.SettlementFailed(s"Biller responded with HTTP $other$reason"))
      }

  private def send(builder: HttpRequest.Builder): Either[String, HttpResponse[String]] =
    try
      Right(
        client.send(
          builder.timeout(requestTimeout).build(),
          HttpResponse.BodyHandlers.ofString()
        )
      )
    catch
      case _: HttpTimeoutException => Left("request timed out")
      case error: IOException =>
        Left(Option(error.getMessage).getOrElse(error.getClass.getSimpleName))
      case _: InterruptedException =>
        Thread.currentThread().interrupt()
        Left("request interrupted")

  private def parseJson(body: String): Option[JValue] =
    Try(parse(body, useBigDecimalForDouble = true)).toOption

  private def decimalField(body: String, fieldName: String): Option[BigDecimal] =
    parseJson(body).map(_ \ fieldName).collect {
      case JDecimal(value) => value
      case JInt(value)     => BigDecimal(value)
    }

  private def errorCode(body: String): Option[String] =
    parseJson(body).map(_ \ "error").collect { case JString(code) => code }

  private def encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)
