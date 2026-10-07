package com.example.billpayment.application

import java.time.{Clock, Duration, Instant}
import java.util.UUID

import cats.syntax.all.*
import com.example.banking.domain.{AccountId, BillPaymentChargeRequested}
import com.example.banking.ports.MessageBus
import com.example.billpayment.domain.*
import com.example.billpayment.ports.{
  BillerGateway,
  BillPaymentInquiryRepository,
  BillPaymentProcessRepository
}
import com.example.domain.DomainError

final case class BillPaymentInquiryResult(inquiry: BillPaymentInquiry)

final case class BillPaymentAccepted(process: BillPaymentProcess)

private final case class ValidatedInquiry(
    accountId: AccountId,
    billerCode: BillerCode,
    referenceCode1: BillerReference,
    referenceCode2: BillerReference
)

final class BillPaymentService(
    billerGateway: BillerGateway,
    inquiryRepository: BillPaymentInquiryRepository,
    processRepository: BillPaymentProcessRepository,
    messageBus: MessageBus,
    clock: Clock,
    generateInquiryId: () => UUID,
    generatePaymentId: () => UUID
):
  private val InquiryLifetime = Duration.ofMinutes(5)

  def inquire(
      rawAccountId: String,
      rawBillerCode: String,
      rawReferenceCode1: String,
      rawReferenceCode2: String
  ): Either[DomainError, BillPaymentInquiryResult] =
    for
      input <- validateInquiry(
        rawAccountId,
        rawBillerCode,
        rawReferenceCode1,
        rawReferenceCode2
      )
      debt <- billerGateway.fetchDebt(
        input.billerCode,
        input.referenceCode1,
        input.referenceCode2
      )
    yield
      val currentTime = Instant.now(clock)
      val inquiry = BillPaymentInquiry(
        inquiryId = BillPaymentInquiryId(generateInquiryId()),
        accountId = input.accountId,
        debt = debt,
        expiresAt = currentTime.plus(InquiryLifetime),
        status = BillPaymentInquiryStatus.Pending
      )
      inquiryRepository.save(inquiry)
      BillPaymentInquiryResult(inquiry)

  private def validateInquiry(
      rawAccountId: String,
      rawBillerCode: String,
      rawReferenceCode1: String,
      rawReferenceCode2: String
  ): Either[DomainError, ValidatedInquiry] =
    (
      AccountId.from(rawAccountId).leftWiden[DomainError].toValidatedNel,
      BillerCode.from(rawBillerCode).leftWiden[DomainError].toValidatedNel,
      BillerReference
        .from("referenceCode1", rawReferenceCode1)
        .leftWiden[DomainError]
        .toValidatedNel,
      BillerReference
        .from("referenceCode2", rawReferenceCode2)
        .leftWiden[DomainError]
        .toValidatedNel
    ).mapN(ValidatedInquiry.apply)
      .toEither
      .left
      .map(BillPaymentError.InvalidInquiry.apply)

  def confirm(
      rawAccountId: String,
      rawInquiryId: String
  ): Either[DomainError, BillPaymentAccepted] =
    for
      accountId <- AccountId.from(rawAccountId)
      inquiryId <- BillPaymentInquiryId.from(rawInquiryId)
      inquiry <- inquiryRepository.claim(inquiryId, accountId, Instant.now(clock))
    yield
      val currentTime = Instant.now(clock)
      val process = BillPaymentProcess(
        paymentId = BillPaymentId(generatePaymentId()),
        inquiryId = inquiry.inquiryId,
        accountId = inquiry.accountId,
        debt = inquiry.debt,
        status = BillPaymentStatus.AwaitingAccountCharge,
        resultingBalance = None,
        billerReceipt = None,
        failure = None,
        createdAt = currentTime,
        updatedAt = currentTime
      )
      processRepository.save(process)
      messageBus.publish(
        BillPaymentChargeRequested(
          process.paymentId.value,
          process.accountId,
          process.debt.amount,
          currentTime
        )
      )
      BillPaymentAccepted(process)

  def payment(
      rawAccountId: String,
      rawPaymentId: String
  ): Either[DomainError, BillPaymentProcess] =
    for
      accountId <- AccountId.from(rawAccountId)
      paymentId <- BillPaymentId.from(rawPaymentId)
      process <- processRepository
        .find(paymentId)
        .filter(_.accountId == accountId)
        .toRight(BillPaymentError.PaymentNotFound)
    yield process
