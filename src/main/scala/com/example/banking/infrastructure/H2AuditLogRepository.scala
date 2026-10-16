package com.example.banking.infrastructure

import java.sql.{PreparedStatement, ResultSet}

import com.example.banking.domain.AuditLogEntry
import com.example.banking.ports.AuditLogRepository
import com.example.infrastructure.Database

final class H2AuditLogRepository(database: Database) extends AuditLogRepository:
  override def append(entry: AuditLogEntry): Unit =
    database.withConnection { connection =>
      val statement = connection.prepareStatement(
        """
        INSERT INTO audit_log (transaction_id, operation, account_id, amount, resulting_balance, occurred_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """
      )
      try
        statement.setString(1, entry.transactionId.toString)
        statement.setString(2, entry.operation.toString)
        statement.setString(3, entry.accountId.value)
        statement.setBigDecimal(4, entry.amount.amount.bigDecimal)
        statement.setBigDecimal(5, entry.resultingBalance.bigDecimal)
        statement.setTimestamp(6, java.sql.Timestamp.from(entry.occurredAt))
        statement.executeUpdate()
      finally statement.close()
    }

  def entries: Vector[AuditLogEntry] =
    database.withConnection { connection =>
      val statement = connection.prepareStatement(
        "SELECT transaction_id, operation, account_id, amount, resulting_balance, occurred_at FROM audit_log ORDER BY audit_id"
      )
      try
        val results = statement.executeQuery()
        readAll(results)
      finally statement.close()
    }

  private def readAll(results: ResultSet): Vector[AuditLogEntry] =
    val builder = Vector.newBuilder[AuditLogEntry]
    while results.next() do
      builder += AuditLogEntry.fromRow(
        results.getString(1),
        results.getString(2),
        results.getString(3),
        results.getBigDecimal(4),
        results.getBigDecimal(5),
        results.getTimestamp(6).toInstant()
      )
    builder.result()
