package com.example.billpayment.ports

import java.time.Instant

import com.example.banking.domain.{
  AccountId
}
import com.example.billpayment.domain.{
  BillPaymentError,
  BillPaymentInquiry,
  BillPaymentInquiryId
}

trait BillPaymentInquiryRepository:
  def save(inquiry: BillPaymentInquiry): Unit

  def claim(
      inquiryId: BillPaymentInquiryId,
      accountId: AccountId,
      currentTime: Instant
  ): Either[BillPaymentError, BillPaymentInquiry]

  def release(inquiryId: BillPaymentInquiryId): Unit
  def complete(inquiryId: BillPaymentInquiryId): Unit
