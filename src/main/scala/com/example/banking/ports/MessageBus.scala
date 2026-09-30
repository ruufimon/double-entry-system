package com.example.banking.ports

import com.example.banking.domain.DomainEvent

trait MessageBus:
  def publish(event: DomainEvent): Unit
  def subscribe(handler: DomainEvent => Unit): Unit
