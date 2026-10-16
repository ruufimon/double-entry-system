package com.example.banking.infrastructure

import java.time.Instant
import java.sql.{PreparedStatement, ResultSet}

import com.example.banking.domain.{Account, AccountId, AccountOpening, BankingError}
import com.example.banking.ports.AccountRepository
import com.example.domain.DomainError
import com.example.infrastructure.Database

final class H2AccountRepository(database: Database) extends AccountRepository:
  override def open(accountId: AccountId): AccountOpening =
    val openedAt = Instant.now()
    database.withTransaction { connection =>
      val statement = connection.prepareStatement(
        "INSERT INTO accounts (account_id, opened_at) VALUES (?, ?)"
      )
      try
        statement.setString(1, accountId.value)
        statement.setTimestamp(2, java.sql.Timestamp.from(openedAt))
        val inserted = statement.executeUpdate() > 0
        AccountOpening(Account(accountId, BigDecimal(0)), inserted)
      catch
        case _: java.sql.SQLIntegrityConstraintViolationException =>
          AccountOpening(Account(accountId, BigDecimal(0)), false)
      finally statement.close()
    }

  override def find(accountId: AccountId): Either[DomainError, Account] =
    database.withConnection { connection =>
      val statement = connection.prepareStatement("SELECT 1 FROM accounts WHERE account_id = ?")
      try
        statement.setString(1, accountId.value)
        val found = statement.executeQuery().next()
        if found then Right(Account(accountId, BigDecimal(0)))
        else Left(BankingError.AccountNotFound(accountId.value))
      finally statement.close()
    }

  override def all: Vector[Account] =
    database.withConnection { connection =>
      val statement = connection.prepareStatement("SELECT account_id FROM accounts ORDER BY account_id")
      try
        val results = statement.executeQuery()
        buildAll(results)
      finally statement.close()
    }

  private def buildAll(results: ResultSet): Vector[Account] =
    val builder = Vector.newBuilder[Account]
    while results.next() do
      builder += Account(requireAccountId(results.getString(1)), BigDecimal(0))
    builder.result()

  private def requireAccountId(rawAccountId: String): AccountId =
    AccountId.from(rawAccountId) match
      case Right(accountId) => accountId
      case Left(error) =>
        throw new IllegalStateException(s"Stored account ID is invalid: ${error.message}")
