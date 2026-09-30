package com.example.messaging

import java.time.Instant

trait InternalMessage derives CanEqual:
  def occurredAt: Instant
