package com.example.banking.ports

import com.example.messaging.InternalMessage

trait MessageBus:
  def publish(message: InternalMessage): Unit
  def subscribe(handler: InternalMessage => Unit): Unit
