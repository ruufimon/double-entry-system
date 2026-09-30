package com.example.billpayment.application

import java.time.{Clock, Duration, Instant}
import java.util.UUID

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
      accountId <- AccountId.from(rawAccountId)
      billerCode <- BillerCode.from(rawBillerCode)
      referenceCode1 <- BillerReference.from("referenceCode1", rawReferenceCode1)
      referenceCode2 <- BillerReference.from("referenceCode2", rawReferenceCode2)
      debt <- billerGateway.fetchDebt(billerCode, referenceCode1, referenceCode2)
    yield
      val currentTime = Instant.now(clock)
      val inquiry = BillPaymentInquiry(
        inquiryId = BillPaymentInquiryId(generateInquiryId()),
        accountId = accountId,
        debt = debt,
        expiresAt = currentTime.plus(InquiryLifetime),
        status = BillPaymentInquiryStatus.Pending
      )
      inquiryRepository.save(inquiry)
      BillPaymentInquiryResult(inquiry)

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
