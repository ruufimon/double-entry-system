package com.example.banking.application

import java.time.{Clock, Duration, Instant}
import java.util.UUID

import com.example.banking.domain.{
  Account,
  AccountId,
  BankingError,
  BillerCode,
  BillerReceipt,
  BillerReference,
  BillPaymentCompleted,
  BillPaymentId,
  BillPaymentInquiry,
  BillPaymentInquiryId,
  BillPaymentInquiryStatus
}
import com.example.banking.ports.{
  AccountRepository,
  BillerGateway,
  BillPaymentInquiryRepository,
  MessageBus
}

final case class BillPaymentInquiryResult(inquiry: BillPaymentInquiry)

final case class BillPaymentResult(
    inquiry: BillPaymentInquiry,
    account: Account,
    billerReceipt: BillerReceipt,
    event: BillPaymentCompleted
)

final class BillPaymentService(
    accountRepository: AccountRepository,
    billerGateway: BillerGateway,
    inquiryRepository: BillPaymentInquiryRepository,
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
  ): Either[BankingError, BillPaymentInquiryResult] =
    for
      accountId <- AccountId.from(rawAccountId)
      _ <- accountRepository.find(accountId)
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
  ): Either[BankingError, BillPaymentResult] =
    for
      accountId <- AccountId.from(rawAccountId)
      inquiryId <- BillPaymentInquiryId.from(rawInquiryId)
      inquiry <- inquiryRepository.claim(inquiryId, accountId, Instant.now(clock))
      result <- completePayment(inquiry)
    yield result

  private def completePayment(
      inquiry: BillPaymentInquiry
  ): Either[BankingError, BillPaymentResult] =
    accountRepository.withdraw(inquiry.accountId, inquiry.debt.amount) match
      case Left(error) =>
        inquiryRepository.release(inquiry.inquiryId)
        Left(error)
      case Right(account) =>
        settleWithBiller(inquiry, account)

  private def settleWithBiller(
      inquiry: BillPaymentInquiry,
      account: Account
  ): Either[BankingError, BillPaymentResult] =
    val paymentId = BillPaymentId(generatePaymentId())
    val paidAt = Instant.now(clock)

    billerGateway.settle(inquiry.debt, paymentId, paidAt) match
      case Left(error) =>
        accountRepository.refund(inquiry.accountId, inquiry.debt.amount)
        inquiryRepository.release(inquiry.inquiryId)
        Left(error)
      case Right(receipt) =>
        inquiryRepository.complete(inquiry.inquiryId)
        val event = BillPaymentCompleted(
          paymentId = paymentId,
          inquiryId = inquiry.inquiryId,
          accountId = inquiry.accountId,
          billerCode = inquiry.debt.billerCode,
          amount = inquiry.debt.amount,
          resultingBalance = account.balance,
          billerReceiptCode = receipt.receiptCode,
          occurredAt = receipt.paidAt
        )
        messageBus.publish(event)
        Right(BillPaymentResult(inquiry, account, receipt, event))
