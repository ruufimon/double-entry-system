package com.example.billpayment.infrastructure

import java.sql.{Connection, PreparedStatement}
import java.time.Instant

import com.example.banking.domain.AccountId
import com.example.billpayment.domain.{
  BillPaymentError,
  BillPaymentInquiry,
  BillPaymentInquiryId,
  BillPaymentInquiryStatus,
  BillerCode,
  BillerDebt,
  BillerReference
}
import com.example.billpayment.ports.BillPaymentInquiryRepository
import com.example.infrastructure.Database

final class H2BillPaymentInquiryRepository(database: Database) extends BillPaymentInquiryRepository:
  override def save(inquiry: BillPaymentInquiry): Unit =
    database.withConnection { connection =>
      upsert(connection, inquiry)
    }

  override def claim(
      inquiryId: BillPaymentInquiryId,
      accountId: AccountId,
      currentTime: Instant
  ): Either[BillPaymentError, BillPaymentInquiry] =
    database.withTransaction { connection =>
      findForUpdate(connection, inquiryId) match
        case None => Left(BillPaymentError.InquiryNotFound)
        case Some(inquiry) =>
          def claimNow: Either[BillPaymentError, BillPaymentInquiry] =
            if inquiry.accountId != accountId then Left(BillPaymentError.InquiryNotFound)
            else if inquiry.status == BillPaymentInquiryStatus.Completed then Left(BillPaymentError.AlreadyCompleted)
            else if inquiry.status == BillPaymentInquiryStatus.Processing then Left(BillPaymentError.InProgress)
            else if !currentTime.isBefore(inquiry.expiresAt) then Left(BillPaymentError.InquiryExpired)
            else
              val claimed = inquiry.copy(status = BillPaymentInquiryStatus.Processing)
              upsert(connection, claimed)
              Right(claimed)
          claimNow
    }

  override def release(inquiryId: BillPaymentInquiryId): Unit =
    database.withConnection { connection =>
      setStatusIf(connection, inquiryId, BillPaymentInquiryStatus.Processing, BillPaymentInquiryStatus.Pending)
    }

  override def complete(inquiryId: BillPaymentInquiryId): Unit =
    database.withConnection { connection =>
      setStatusIf(connection, inquiryId, BillPaymentInquiryStatus.Processing, BillPaymentInquiryStatus.Completed)
    }

  private def findForUpdate(
      connection: Connection,
      inquiryId: BillPaymentInquiryId
  ): Option[BillPaymentInquiry] =
    val statement = connection.prepareStatement(
      "SELECT inquiry_id, account_id, biller_code, reference_code1, reference_code2, amount, expires_at, status " +
        "FROM bill_payment_inquiries WHERE inquiry_id = ? FOR UPDATE"
    )
    readOne(statement, inquiryId)

  private def readOne(
      statement: PreparedStatement,
      inquiryId: BillPaymentInquiryId
  ): Option[BillPaymentInquiry] =
    try
      statement.setString(1, inquiryId.value.toString)
      val rows = statement.executeQuery()
      if !rows.next() then None
      else
        Some(
          BillPaymentInquiry(
            inquiryId = BillPaymentInquiryId.unsafe(rows.getString(1)),
            accountId = AccountId.unsafe(rows.getString(2)),
            debt = BillerDebt.from(
              BillerCode.unsafe(rows.getString(3)),
              BillerReference.unsafe(rows.getString(4)),
              BillerReference.unsafe(rows.getString(5)),
              BigDecimal(rows.getBigDecimal(6))
            ),
            expiresAt = rows.getTimestamp(7).toInstant(),
            status = BillPaymentInquiryStatus.unsafe(rows.getString(8))
          )
        )
    finally statement.close()

  private def upsert(connection: Connection, inquiry: BillPaymentInquiry): Unit =
    val statement = connection.prepareStatement(
      """
      MERGE INTO bill_payment_inquiries
        (inquiry_id, account_id, biller_code, reference_code1, reference_code2, amount, expires_at, status)
      KEY (inquiry_id)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      """
    )
    try
      statement.setString(1, inquiry.inquiryId.value.toString)
      statement.setString(2, inquiry.accountId.value)
      statement.setString(3, inquiry.debt.billerCode.value)
      statement.setString(4, inquiry.debt.referenceCode1.value)
      statement.setString(5, inquiry.debt.referenceCode2.value)
      statement.setBigDecimal(6, inquiry.debt.amount.amount.bigDecimal)
      statement.setTimestamp(7, java.sql.Timestamp.from(inquiry.expiresAt))
      statement.setString(8, inquiry.status.toString)
      statement.executeUpdate()
    finally statement.close()

  private def setStatusIf(
      connection: Connection,
      inquiryId: BillPaymentInquiryId,
      expected: BillPaymentInquiryStatus,
      next: BillPaymentInquiryStatus
  ): Unit =
    val statement = connection.prepareStatement(
      "UPDATE bill_payment_inquiries SET status = ? WHERE inquiry_id = ? AND status = ?"
    )
    try
      statement.setString(1, next.toString)
      statement.setString(2, inquiryId.value.toString)
      statement.setString(3, expected.toString)
      statement.executeUpdate()
    finally statement.close()
