package com.example.billpayment.infrastructure

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

import com.example.banking.domain.{
  AccountId
}
import com.example.billpayment.domain.{
  BillPaymentError,
  BillPaymentInquiry,
  BillPaymentInquiryId,
  BillPaymentInquiryStatus
}
import com.example.billpayment.ports.BillPaymentInquiryRepository

final class InMemoryBillPaymentInquiryRepository extends BillPaymentInquiryRepository:
  private val inquiries = new ConcurrentHashMap[BillPaymentInquiryId, BillPaymentInquiry]()

  override def save(inquiry: BillPaymentInquiry): Unit =
    inquiries.put(inquiry.inquiryId, inquiry)

  override def claim(
      inquiryId: BillPaymentInquiryId,
      accountId: AccountId,
      currentTime: Instant
  ): Either[BillPaymentError, BillPaymentInquiry] =
    inquiries.synchronized {
      Option(inquiries.get(inquiryId)) match
        case None => Left(BillPaymentError.InquiryNotFound)
        case Some(inquiry) if inquiry.accountId != accountId =>
          Left(BillPaymentError.InquiryNotFound)
        case Some(inquiry) if inquiry.status == BillPaymentInquiryStatus.Completed =>
          Left(BillPaymentError.AlreadyCompleted)
        case Some(inquiry) if inquiry.status == BillPaymentInquiryStatus.Processing =>
          Left(BillPaymentError.InProgress)
        case Some(inquiry) if !currentTime.isBefore(inquiry.expiresAt) =>
          Left(BillPaymentError.InquiryExpired)
        case Some(inquiry) =>
          val claimedInquiry = inquiry.copy(status = BillPaymentInquiryStatus.Processing)
          inquiries.put(inquiryId, claimedInquiry)
          Right(claimedInquiry)
    }

  override def release(inquiryId: BillPaymentInquiryId): Unit =
    inquiries.synchronized {
      Option(inquiries.get(inquiryId)).foreach { inquiry =>
        if inquiry.status == BillPaymentInquiryStatus.Processing then
          inquiries.put(inquiryId, inquiry.copy(status = BillPaymentInquiryStatus.Pending))
      }
    }

  override def complete(inquiryId: BillPaymentInquiryId): Unit =
    inquiries.synchronized {
      Option(inquiries.get(inquiryId)).foreach { inquiry =>
        inquiries.put(inquiryId, inquiry.copy(status = BillPaymentInquiryStatus.Completed))
      }
    }
