package com.example.billpayment.domain

import java.time.Instant
import java.util.UUID

import scala.util.Try

import cats.data.NonEmptyList
import com.example.banking.domain.{AccountId, DomainEvent, Money}
import com.example.domain.DomainError

enum BillPaymentError(val code: String, val message: String)
    extends DomainError
    derives CanEqual:
  case InvalidAmount(details: String)
      extends BillPaymentError("invalid_bill_payment_amount", details)
  case InvalidBillerCode
      extends BillPaymentError(
        "invalid_biller_code",
        "Biller code must contain 1 to 64 letters, numbers, underscores, or hyphens"
      )
  case InvalidBillerReference(fieldName: String)
      extends BillPaymentError(
        "invalid_biller_reference",
        s"$fieldName must contain 1 to 128 characters"
      )
  case InvalidInquiry(errors: NonEmptyList[DomainError])
      extends BillPaymentError(
        "invalid_bill_payment_inquiry",
        errors.toList.map(_.message).mkString("; ")
      )
  case InvalidInquiryId
      extends BillPaymentError(
        "invalid_bill_payment_inquiry_id",
        "Inquiry ID must be a valid UUID"
      )
  case InvalidPaymentId
      extends BillPaymentError(
        "invalid_bill_payment_id",
        "Payment ID must be a valid UUID"
      )
  case BillerNotFound(billerCode: String)
      extends BillPaymentError("biller_not_found", s"Biller '$billerCode' was not found")
  case BillNotFound
      extends BillPaymentError("bill_not_found", "No bill was found for the supplied references")
  case BillNotPayable
      extends BillPaymentError("bill_not_payable", "The bill has already been paid")
  case InquiryNotFound
      extends BillPaymentError("bill_payment_inquiry_not_found", "Bill payment inquiry was not found")
  case PaymentNotFound
      extends BillPaymentError("bill_payment_not_found", "Bill payment was not found")
  case InquiryExpired
      extends BillPaymentError("bill_payment_inquiry_expired", "Bill payment inquiry has expired")
  case AlreadyCompleted
      extends BillPaymentError("bill_payment_already_completed", "Bill payment is already completed")
  case InProgress
      extends BillPaymentError("bill_payment_in_progress", "Bill payment confirmation is in progress")
  case SettlementFailed(details: String)
      extends BillPaymentError("biller_settlement_failed", details)
  case CompensationFailed(details: String)
      extends BillPaymentError("bill_payment_compensation_failed", details)

object BillPaymentAmount:
  def from(amount: BigDecimal): Either[BillPaymentError, Money] =
    Money.positive(amount).left.map(BillPaymentError.InvalidAmount.apply)

final case class BillPaymentId(value: UUID) derives CanEqual

object BillPaymentId:
  def from(rawPaymentId: String): Either[BillPaymentError, BillPaymentId] =
    Try(UUID.fromString(rawPaymentId)).toEither
      .map(BillPaymentId.apply)
      .left
      .map(_ => BillPaymentError.InvalidPaymentId)

final case class BillPaymentInquiryId(value: UUID) derives CanEqual

object BillPaymentInquiryId:
  def from(rawInquiryId: String): Either[BillPaymentError, BillPaymentInquiryId] =
    Try(UUID.fromString(rawInquiryId)).toEither
      .map(BillPaymentInquiryId.apply)
      .left
      .map(_ => BillPaymentError.InvalidInquiryId)

final case class BillerCode private (value: String) derives CanEqual

object BillerCode:
  private val ValidBillerCode = "[A-Za-z0-9_-]{1,64}".r

  def from(rawBillerCode: String): Either[BillPaymentError, BillerCode] =
    val normalizedBillerCode = Option(rawBillerCode).fold("")(_.trim)
    normalizedBillerCode match
      case ValidBillerCode() => Right(BillerCode(normalizedBillerCode))
      case _                 => Left(BillPaymentError.InvalidBillerCode)

final case class BillerReference private (value: String) derives CanEqual

object BillerReference:
  def from(fieldName: String, rawReference: String): Either[BillPaymentError, BillerReference] =
    val normalizedReference = Option(rawReference).fold("")(_.trim)
    if normalizedReference.nonEmpty && normalizedReference.length <= 128 then
      Right(BillerReference(normalizedReference))
    else
      Left(BillPaymentError.InvalidBillerReference(fieldName))

final case class BillerDebt(
    billerCode: BillerCode,
    referenceCode1: BillerReference,
    referenceCode2: BillerReference,
    amount: Money
) derives CanEqual

final case class BillerReceipt(receiptCode: String, paidAt: Instant) derives CanEqual

enum BillPaymentInquiryStatus derives CanEqual:
  case Pending, Processing, Completed

enum BillPaymentStatus derives CanEqual:
  case AwaitingAccountCharge, Settling, Reversing, Completed, Failed, ManualReview

final case class BillPaymentFailure(code: String, message: String) derives CanEqual

final case class BillPaymentProcess(
    paymentId: BillPaymentId,
    inquiryId: BillPaymentInquiryId,
    accountId: AccountId,
    debt: BillerDebt,
    status: BillPaymentStatus,
    resultingBalance: Option[BigDecimal],
    billerReceipt: Option[BillerReceipt],
    failure: Option[BillPaymentFailure],
    createdAt: Instant,
    updatedAt: Instant
) derives CanEqual

final case class BillPaymentInquiry(
    inquiryId: BillPaymentInquiryId,
    accountId: AccountId,
    debt: BillerDebt,
    expiresAt: Instant,
    status: BillPaymentInquiryStatus
) derives CanEqual

final case class BillPaymentCompleted(
    paymentId: BillPaymentId,
    inquiryId: BillPaymentInquiryId,
    accountId: AccountId,
    billerCode: BillerCode,
    amount: Money,
    resultingBalance: BigDecimal,
    billerReceiptCode: String,
    occurredAt: Instant
) extends DomainEvent
