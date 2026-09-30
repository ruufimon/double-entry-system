package com.example.banking.infrastructure

import java.time.Duration
import java.util.concurrent.{
  ConcurrentLinkedQueue,
  CopyOnWriteArrayList,
  Executors,
  RejectedExecutionException,
  TimeUnit
}

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import com.example.banking.ports.MessageBus
import com.example.messaging.InternalMessage

final class LocalMessageBus extends MessageBus, AutoCloseable:
  private val handlers = new CopyOnWriteArrayList[InternalMessage => Unit]()
  private val failures = new ConcurrentLinkedQueue[Throwable]()
  private val executor = Executors.newSingleThreadExecutor { runnable =>
    val thread = new Thread(runnable, "local-message-bus")
    thread.setDaemon(true)
    thread
  }
  private val stateMonitor = new Object()
  private var outstandingMessages = 0
  private var closed = false

  override def publish(message: InternalMessage): Unit =
    stateMonitor.synchronized {
      if closed then throw new IllegalStateException("Message bus is closed")
      outstandingMessages += 1
    }

    try
      executor.execute(() => dispatch(message))
    catch
      case error: RejectedExecutionException =>
        messageCompleted()
        throw error

  override def subscribe(handler: InternalMessage => Unit): Unit =
    handlers.add(handler)

  def awaitIdle(timeout: Duration): Boolean =
    val deadline = System.nanoTime() + timeout.toNanos
    val becameIdle = stateMonitor.synchronized {
      var remainingNanos = deadline - System.nanoTime()
      while outstandingMessages > 0 && remainingNanos > 0 do
        TimeUnit.NANOSECONDS.timedWait(stateMonitor, remainingNanos)
        remainingNanos = deadline - System.nanoTime()
      outstandingMessages == 0
    }
    Option(failures.poll()).foreach { failure =>
      throw new IllegalStateException("Internal message handler failed", failure)
    }
    becameIdle

  override def close(): Unit =
    awaitIdle(Duration.ofSeconds(5))
    stateMonitor.synchronized {
      closed = true
    }
    executor.shutdown()
    executor.awaitTermination(5, TimeUnit.SECONDS)

  private def dispatch(message: InternalMessage): Unit =
    try
      handlers.asScala.foreach { handler =>
        try handler(message)
        catch
          case NonFatal(error) => failures.add(error)
      }
    finally messageCompleted()

  private def messageCompleted(): Unit =
    stateMonitor.synchronized {
      outstandingMessages -= 1
      if outstandingMessages == 0 then stateMonitor.notifyAll()
    }
