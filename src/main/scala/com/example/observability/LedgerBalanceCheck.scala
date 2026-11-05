package com.example.observability

import java.time.Duration
import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import java.util.concurrent.atomic.AtomicLong

import scala.util.Using
import scala.util.control.NonFatal

import com.example.infrastructure.Database
import io.micrometer.core.instrument.{Gauge, MeterRegistry, Timer}
import org.slf4j.LoggerFactory

enum LedgerCheckResult(val label: String):
  case Ok(unbalanced: Long) extends LedgerCheckResult("ok")
  case Skipped extends LedgerCheckResult("skipped")
  case Failed extends LedgerCheckResult("failed")

/** Recomputes, on a schedule, how many ledger transactions do not balance.
  *
  * Exposes `banking_ledger_unbalanced_transactions` (-1 until the first successful run) and
  * `banking_ledger_check_duration_seconds{result}`. The check must never queue behind customer
  * traffic: it is `skipped` when the pool has no idle connection, and its query carries a short
  * timeout so it cannot hold one of the few pooled connections for long. A timeout is also
  * `skipped`, any other failure is `failed`, and both leave the gauge at its last good value.
  */
final class LedgerBalanceCheck(
    database: Database,
    registry: MeterRegistry,
    interval: Duration = Duration.ofSeconds(60),
    queryTimeout: Duration = Duration.ofSeconds(2)
) extends AutoCloseable:
  private val logger = LoggerFactory.getLogger(getClass)
  private val unbalanced = new AtomicLong(-1L)
  private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task =>
    val thread = new Thread(task, "ledger-balance-check")
    thread.setDaemon(true)
    thread
  }

  Gauge
    .builder(LedgerBalanceCheck.GaugeName, unbalanced, (value: AtomicLong) => value.get.toDouble)
    .description("Ledger transactions whose entries do not sum to zero; -1 before the first check")
    .strongReference(true)
    .register(registry)

  def start(): Unit =
    executor.scheduleAtFixedRate(() => runOnce(), 0L, interval.toMillis, TimeUnit.MILLISECONDS)

  /** Runs one check now and records it. Public for tests. */
  def runOnce(): LedgerCheckResult =
    val started = System.nanoTime()
    val result =
      try
        if poolBusy() then
          logger.warn("Ledger balance check skipped: no idle database connection")
          LedgerCheckResult.Skipped
        else LedgerCheckResult.Ok(countUnbalanced())
      catch
        case timeout: java.sql.SQLException if LedgerBalanceCheck.isQueryTimeout(timeout) =>
          logger.warn(s"Ledger balance check timed out after ${queryTimeout.toMillis} ms", timeout)
          LedgerCheckResult.Skipped
        case NonFatal(error) =>
          logger.warn("Ledger balance check failed", error)
          LedgerCheckResult.Failed
    result match
      case LedgerCheckResult.Ok(count) =>
        unbalanced.set(count)
        if count > 0 then logger.error(s"Ledger has $count unbalanced transaction(s)")
      case _ => ()
    Timer
      .builder(LedgerBalanceCheck.TimerName)
      .description("Ledger balance check duration by result")
      .tag("result", result.label)
      .register(registry)
      .record(System.nanoTime() - started, TimeUnit.NANOSECONDS)
    result

  /** Hikari's checkout blocks for the full connection timeout when every connection is in use, so
    * look first. Counting checked-out connections rather than idle ones keeps an unreachable
    * database (no connections at all) a `failed` check. Racy by design: losing the race only means
    * waiting like any other caller.
    */
  private def poolBusy(): Boolean =
    val dataSource = database.dataSource
    !dataSource.isClosed &&
    Option(dataSource.getHikariPoolMXBean).exists(_.getActiveConnections >= dataSource.getMaximumPoolSize)

  private def countUnbalanced(): Long =
    database.withConnection { connection =>
      Using.resource(connection.prepareStatement(LedgerBalanceCheck.Query)) { statement =>
        statement.setQueryTimeout(queryTimeout.toSeconds.toInt.max(1))
        Using.resource(statement.executeQuery()) { rows =>
          rows.next()
          rows.getLong(1)
        }
      }
    }

  override def close(): Unit =
    executor.shutdownNow()
    executor.awaitTermination(2, TimeUnit.SECONDS)

object LedgerBalanceCheck:
  val GaugeName = "banking.ledger.unbalanced.transactions"
  val TimerName = "banking.ledger.check.duration"

  /** PostgreSQL's SQLSTATE for a statement cancelled by `setQueryTimeout`. pgjdbc raises it as a
    * plain PSQLException, not SQLTimeoutException.
    */
  val QueryCanceledSqlState = "57014"

  def isQueryTimeout(error: java.sql.SQLException): Boolean =
    error.isInstanceOf[java.sql.SQLTimeoutException] || error.getSQLState == QueryCanceledSqlState

  /** Same sign convention as H2LedgerRepository: credits positive, debits negative. */
  val Query: String =
    """SELECT COUNT(*) FROM (
      |  SELECT transaction_id
      |  FROM ledger_entries
      |  GROUP BY transaction_id
      |  HAVING SUM(CASE WHEN direction = 'Credit' THEN amount ELSE -amount END) <> 0
      |) unbalanced""".stripMargin
