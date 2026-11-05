package com.example.observability

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

import scala.util.Using

import com.example.infrastructure.Database
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class LedgerBalanceCheckSpec extends AnyFunSuite with Matchers:
  private def withDatabase(test: (Database, SimpleMeterRegistry, LedgerBalanceCheck) => Unit): Unit =
    val database = Database.open(Database.h2MemoryUrl(s"ledger-check-${UUID.randomUUID()}"))
    val registry = new SimpleMeterRegistry()
    val check = new LedgerBalanceCheck(database, registry)
    try test(database, registry, check)
    finally
      check.close()
      database.close()

  private def gauge(registry: SimpleMeterRegistry): Double =
    registry.get(LedgerBalanceCheck.GaugeName).gauge().value()

  private def timerCount(registry: SimpleMeterRegistry, result: String): Long =
    Option(registry.find(LedgerBalanceCheck.TimerName).tag("result", result).timer()).map(_.count()).getOrElse(0L)

  private def insertTransaction(database: Database, entries: Seq[(String, BigDecimal)]): Unit =
    val transactionId = UUID.randomUUID().toString
    database.withTransaction { connection =>
      Using.resource(
        connection.prepareStatement(
          "INSERT INTO ledger_transactions (transaction_id, operation, occurred_at, reverses_transaction_id, adjusts_transaction_id) VALUES (?, ?, ?, NULL, NULL)"
        )
      ) { statement =>
        statement.setString(1, transactionId)
        statement.setString(2, "deposit")
        statement.setTimestamp(3, Timestamp.from(Instant.now()))
        statement.executeUpdate()
      }
      entries.zipWithIndex.foreach { case ((direction, amount), index) =>
        Using.resource(
          connection.prepareStatement(
            "INSERT INTO ledger_entries (transaction_id, entry_index, account, customer_account_id, direction, amount, currency) VALUES (?, ?, ?, ?, ?, ?, ?)"
          )
        ) { statement =>
          statement.setString(1, transactionId)
          statement.setInt(2, index)
          statement.setString(3, if direction == "Credit" then "customer:acc" else "cash")
          statement.setString(4, "acc")
          statement.setString(5, direction)
          statement.setBigDecimal(6, amount.bigDecimal)
          statement.setString(7, "THB")
          statement.executeUpdate()
        }
      }
    }

  test("gauge is -1 before the first check") {
    withDatabase { (_, registry, _) =>
      gauge(registry) shouldBe -1.0
    }
  }

  test("a balanced ledger reports zero unbalanced transactions") {
    withDatabase { (database, registry, check) =>
      insertTransaction(database, Seq("Debit" -> BigDecimal("10.00"), "Credit" -> BigDecimal("10.00")))
      check.runOnce() shouldBe LedgerCheckResult.Ok(0)
      gauge(registry) shouldBe 0.0
      timerCount(registry, "ok") shouldBe 1
    }
  }

  test("a transaction whose entries do not sum to zero is counted") {
    withDatabase { (database, registry, check) =>
      insertTransaction(database, Seq("Debit" -> BigDecimal("10.00"), "Credit" -> BigDecimal("10.00")))
      insertTransaction(database, Seq("Credit" -> BigDecimal("5.00")))
      insertTransaction(database, Seq("Debit" -> BigDecimal("7.00"), "Credit" -> BigDecimal("6.99")))
      check.runOnce() shouldBe LedgerCheckResult.Ok(2)
      gauge(registry) shouldBe 2.0
    }
  }

  test("a failing database records failed and keeps the last good value") {
    val database = Database.open(Database.h2MemoryUrl(s"ledger-check-${UUID.randomUUID()}"))
    val registry = new SimpleMeterRegistry()
    val check = new LedgerBalanceCheck(database, registry)
    try
      check.runOnce() shouldBe LedgerCheckResult.Ok(0)
      database.close()
      check.runOnce() shouldBe LedgerCheckResult.Failed
      gauge(registry) shouldBe 0.0
      timerCount(registry, "failed") shouldBe 1
    finally check.close()
  }
