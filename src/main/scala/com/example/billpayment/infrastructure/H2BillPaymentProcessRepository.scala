package com.example.billpayment.infrastructure

import java.sql.{Connection, PreparedStatement}

import com.example.banking.domain.AccountId
import com.example.billpayment.domain.{
  BillPaymentId,
  BillPaymentProcess,
  BillPaymentStatus,
  BillPaymentFailure,
  BillerCode,
  BillerDebt,
  BillerReference,
  BillerReceipt,
  BillPaymentInquiryId
}
import com.example.billpayment.ports.BillPaymentProcessRepository
import com.example.infrastructure.Database

final class H2BillPaymentProcessRepository(database: Database) extends BillPaymentProcessRepository:
  override def save(process: BillPaymentProcess): Unit =
    database.withConnection { connection =>
      upsert(connection, process)
    }

  private def findNamedSql =
    """
    SELECT payment_id, inquiry_id, account_id, biller_code, reference_code1, reference_code2,
           amount, status, resulting_balance, receipt_code, receipt_paid_at,
           failure_code, failure_message, created_at, updated_at
    FROM bill_payment_processes
    """

  override def find(paymentId: BillPaymentId): Option[BillPaymentProcess] =
    database.withConnection { connection =>
      readOne(
        connection.prepareStatement(findNamedSql + " WHERE payment_id = ?"),
        paymentId
      )
    }

  override def update(
      paymentId: BillPaymentId
  )(transition: BillPaymentProcess => BillPaymentProcess): Option[BillPaymentProcess] =
    database.withTransaction { connection =>
      val current = readOne(
        connection.prepareStatement(findNamedSql + " WHERE payment_id = ? FOR UPDATE"),
        paymentId
      )
      current.map { process =>
        val updated = transition(process)
        upsert(connection, updated)
        updated
      }
    }

  private def readOne(statement: PreparedStatement, paymentId: BillPaymentId): Option[BillPaymentProcess] =
    try
      statement.setString(1, paymentId.value.toString)
      val rows = statement.executeQuery()
      if !rows.next() then None
      else Some(rowToProcess(rows))
    finally statement.close()

  private def rowToProcess(rows: java.sql.ResultSet): BillPaymentProcess =
    val resultingBalance = Option(rows.getBigDecimal("resulting_balance")).map(BigDecimal.apply)
    val receiptCode = Option(rows.getString("receipt_code"))
    val receiptPaidAt = Option(rows.getTimestamp("receipt_paid_at")).map(_.toInstant())
    val receipt =
      for code <- receiptCode; paidAt <- receiptPaidAt yield BillerReceipt(code, paidAt)
    val failureCode = Option(rows.getString("failure_code"))
    val failureMessage = Option(rows.getString("failure_message"))
    val failure =
      for code <- failureCode; message <- failureMessage yield BillPaymentFailure(code, message)
    BillPaymentProcess(
      paymentId = BillPaymentId.unsafe(rows.getString("payment_id")),
      inquiryId = BillPaymentInquiryId.unsafe(rows.getString("inquiry_id")),
      accountId = AccountId.unsafe(rows.getString("account_id")),
      debt = BillerDebt.from(
        BillerCode.unsafe(rows.getString("biller_code")),
        BillerReference.unsafe(rows.getString("reference_code1")),
        BillerReference.unsafe(rows.getString("reference_code2")),
        BigDecimal(rows.getBigDecimal("amount"))
      ),
      status = BillPaymentStatus.unsafe(rows.getString("status")),
      resultingBalance = resultingBalance,
      billerReceipt = receipt,
      failure = failure,
      createdAt = rows.getTimestamp("created_at").toInstant(),
      updatedAt = rows.getTimestamp("updated_at").toInstant()
    )

  private def upsert(connection: Connection, process: BillPaymentProcess): Unit =
    val statement = connection.prepareStatement(
      """
      MERGE INTO bill_payment_processes
        (payment_id, inquiry_id, account_id, biller_code, reference_code1, reference_code2,
         amount, status, resulting_balance, receipt_code, receipt_paid_at,
         failure_code, failure_message, created_at, updated_at)
      KEY (payment_id)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """
    )
    try
      statement.setString(1, process.paymentId.value.toString)
      statement.setString(2, process.inquiryId.value.toString)
      statement.setString(3, process.accountId.value)
      statement.setString(4, process.debt.billerCode.value)
      statement.setString(5, process.debt.referenceCode1.value)
      statement.setString(6, process.debt.referenceCode2.value)
      statement.setBigDecimal(7, process.debt.amount.amount.bigDecimal)
      statement.setString(8, process.status.toString)
      setNullableDecimal(statement, 9, process.resultingBalance)
      process.billerReceipt match
        case Some(receipt) =>
          statement.setString(10, receipt.receiptCode)
          statement.setTimestamp(11, java.sql.Timestamp.from(receipt.paidAt))
        case None =>
          statement.setString(10, null)
          statement.setTimestamp(11, null)
      process.failure match
        case Some(failure) =>
          statement.setString(12, failure.code)
          statement.setString(13, failure.message)
        case None =>
          statement.setString(12, null)
          statement.setString(13, null)
      statement.setTimestamp(14, java.sql.Timestamp.from(process.createdAt))
      statement.setTimestamp(15, java.sql.Timestamp.from(process.updatedAt))
      statement.executeUpdate()
    finally statement.close()

  private def setNullableDecimal(statement: PreparedStatement, index: Int, value: Option[BigDecimal]): Unit =
    value match
      case Some(amount) => statement.setBigDecimal(index, amount.bigDecimal)
      case None         => statement.setBigDecimal(index, null)
