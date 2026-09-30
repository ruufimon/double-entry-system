package com.example.banking.ports

import java.time.Instant

import com.example.banking.domain.{
  AccountId,
  BankingError,
  BillPaymentInquiry,
  BillPaymentInquiryId
}

trait BillPaymentInquiryRepository:
  def save(inquiry: BillPaymentInquiry): Unit

  def claim(
      inquiryId: BillPaymentInquiryId,
      accountId: AccountId,
      currentTime: Instant
  ): Either[BankingError, BillPaymentInquiry]

  def release(inquiryId: BillPaymentInquiryId): Unit
  def complete(inquiryId: BillPaymentInquiryId): Unit
