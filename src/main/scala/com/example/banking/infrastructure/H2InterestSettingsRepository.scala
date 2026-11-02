package com.example.banking.infrastructure

import java.sql.Connection
import java.time.{Instant, LocalDate, ZoneId, YearMonth}

import com.example.banking.domain.{AnnualInterestRate, InterestRateRecord}
import com.example.banking.ports.InterestSettingsRepository
import com.example.infrastructure.Database

final class H2InterestSettingsRepository(database: Database) extends InterestSettingsRepository:
  private val createdAt = Instant.now()
  private val defaultRate = InterestRateRecord(
    AnnualInterestRate.Zero,
    YearMonth.from(createdAt.atZone(ZoneId.of("Asia/Bangkok"))).atDay(1),
    createdAt,
    "System default"
  )

  ensureDefaultRate()

  override def rateRecords: Vector[InterestRateRecord] =
    database.withConnection { connection =>
      val statement = connection.prepareStatement(
        "SELECT rate_basis_points, effective_from, created_at, updated_by FROM interest_rate_history ORDER BY rate_id"
      )
      try
        val rows = statement.executeQuery()
        val builder = Vector.newBuilder[InterestRateRecord]
        while rows.next() do
          builder += InterestRateRecord(
            AnnualInterestRate.unsafe(rows.getLong(1)),
            rows.getDate(2).toLocalDate,
            rows.getTimestamp(3).toInstant,
            rows.getString(4)
          )
        builder.result()
      finally statement.close()
    }

  override def saveRate(record: InterestRateRecord): InterestRateRecord =
    database.withTransaction { connection =>
      val statement = connection.prepareStatement(
        "INSERT INTO interest_rate_history (rate_basis_points, effective_from, created_at, updated_by) VALUES (?, ?, ?, ?)"
      )
      try
        statement.setLong(1, record.rate.basisPoints)
        statement.setDate(2, java.sql.Date.valueOf(record.effectiveFrom))
        statement.setTimestamp(3, java.sql.Timestamp.from(record.createdAt))
        statement.setString(4, record.updatedBy)
        statement.executeUpdate()
        record
      finally statement.close()
    }

  override def rateFor(period: YearMonth): AnnualInterestRate =
    database.withConnection { connection =>
      val statement = connection.prepareStatement(
        "SELECT rate_basis_points FROM interest_rate_history WHERE effective_from <= ? ORDER BY effective_from DESC, rate_id DESC FETCH FIRST 1 ROW ONLY"
      )
      try
        statement.setDate(1, java.sql.Date.valueOf(period.atDay(1)))
        val rows = statement.executeQuery()
        if rows.next() then AnnualInterestRate.unsafe(rows.getLong(1))
        else AnnualInterestRate.Zero
      finally statement.close()
    }

  override def nextPeriodToProcess(initialPeriod: YearMonth): YearMonth =
    val fromDatabase = database.withTransaction { connection =>
      val read = connection.prepareStatement(
        "SELECT next_period_start FROM interest_processing_state WHERE state_id = 1 FOR UPDATE"
      )
      try
        val rows = read.executeQuery()
        if rows.next() then rows.getDate(1).toLocalDate
        else
          val insert = connection.prepareStatement(
            "INSERT INTO interest_processing_state (state_id, next_period_start) VALUES (1, ?)"
          )
          try
            insert.setDate(1, java.sql.Date.valueOf(initialPeriod.atDay(1)))
            insert.executeUpdate()
          finally insert.close()
          initialPeriod.atDay(1)
      finally read.close()
    }
    YearMonth.from(fromDatabase)

  override def markPeriodProcessed(period: YearMonth): Unit =
    database.withTransaction { connection =>
      val update = connection.prepareStatement(
        "UPDATE interest_processing_state SET next_period_start = ? WHERE state_id = 1 AND next_period_start <= ?"
      )
      try
        update.setDate(1, java.sql.Date.valueOf(period.plusMonths(1).atDay(1)))
        update.setDate(2, java.sql.Date.valueOf(period.atDay(1)))
        update.executeUpdate()
        ()
      finally update.close()
    }

  private def ensureDefaultRate(): Unit =
    database.withTransaction { connection =>
      val read = connection.prepareStatement("SELECT 1 FROM interest_rate_history FETCH FIRST 1 ROW ONLY")
      try
        if !read.executeQuery().next() then insertDefault(connection)
      finally read.close()
    }

  private def insertDefault(connection: Connection): Unit =
    val statement = connection.prepareStatement(
      "INSERT INTO interest_rate_history (rate_basis_points, effective_from, created_at, updated_by) VALUES (?, ?, ?, ?)"
    )
    try
      statement.setLong(1, defaultRate.rate.basisPoints)
      statement.setDate(2, java.sql.Date.valueOf(defaultRate.effectiveFrom))
      statement.setTimestamp(3, java.sql.Timestamp.from(defaultRate.createdAt))
      statement.setString(4, defaultRate.updatedBy)
      statement.executeUpdate()
    finally statement.close()
