package com.example.banking.infrastructure

import java.util.concurrent.CopyOnWriteArrayList

import scala.jdk.CollectionConverters.*

import com.example.banking.domain.AuditLogEntry
import com.example.banking.ports.AuditLogRepository

final class InMemoryAuditLogRepository extends AuditLogRepository:
  private val auditEntries = new CopyOnWriteArrayList[AuditLogEntry]()

  override def append(entry: AuditLogEntry): Unit =
    auditEntries.add(entry)

  def entries: Vector[AuditLogEntry] =
    auditEntries.asScala.toVector
