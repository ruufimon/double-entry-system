package com.example.banking.application

import java.time.{Clock, Duration, ZoneId}
import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import java.util.logging.Logger

import scala.util.control.NonFatal

final class MonthlyInterestScheduler(
    service: InterestRateService,
    clock: Clock,
    zoneId: ZoneId
) extends AutoCloseable:
  private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task =>
    val thread = new Thread(task, "monthly-interest-processor")
    thread.setDaemon(true)
    thread
  }
  private val logger = Logger.getLogger(getClass.getName)

  def start(): Unit =
    process()
    val now = clock.instant()
    val nextMidnight = now.atZone(zoneId).toLocalDate.plusDays(1).atStartOfDay(zoneId).toInstant
    val initialDelay = Math.max(1L, Duration.between(now, nextMidnight).getSeconds)
    executor.scheduleAtFixedRate(
      () => process(),
      initialDelay,
      TimeUnit.DAYS.toSeconds(1),
      TimeUnit.SECONDS
    )

  private def process(): Unit =
    try
      service.processCompletedPeriods() match
        case Left(error) => logger.warning(error.message)
        case Right(periods) if periods.nonEmpty =>
          logger.info(s"Processed monthly interest for ${periods.map(_.month).mkString(", ")}")
        case _ => ()
    catch case NonFatal(error) => logger.severe(s"Monthly interest processing failed: ${error.getMessage}")

  override def close(): Unit = executor.shutdownNow()
