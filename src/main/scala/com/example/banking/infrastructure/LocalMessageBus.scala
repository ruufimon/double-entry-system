package com.example.banking.infrastructure

import java.util.concurrent.CopyOnWriteArrayList

import scala.jdk.CollectionConverters.*

import com.example.banking.domain.DomainEvent
import com.example.banking.ports.MessageBus

final class LocalMessageBus extends MessageBus:
  private val handlers = new CopyOnWriteArrayList[DomainEvent => Unit]()

  override def publish(event: DomainEvent): Unit =
    handlers.asScala.foreach(handler => handler(event))

  override def subscribe(handler: DomainEvent => Unit): Unit =
    handlers.add(handler)
