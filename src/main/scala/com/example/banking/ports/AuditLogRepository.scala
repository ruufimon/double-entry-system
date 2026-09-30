package com.example.banking.ports

import com.example.banking.domain.AuditLogEntry

trait AuditLogRepository:
  def append(entry: AuditLogEntry): Unit
