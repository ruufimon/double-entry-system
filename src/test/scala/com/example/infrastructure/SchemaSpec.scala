package com.example.infrastructure

import java.nio.file.{Files, Paths}

import scala.jdk.CollectionConverters.*
import scala.util.Using

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class SchemaSpec extends AnyFunSuite with Matchers:
  test("MigrationFiles lists every migration in db/migration") {
    val onDisk = Using.resource(Files.list(Paths.get("src/main/resources/db/migration"))) { files =>
      files.iterator.asScala.map(_.getFileName.toString).toSet
    }

    Schema.MigrationFiles.toSet shouldBe onDisk
  }

  test("migrate applies every listed migration in order") {
    val database = Database.open(Database.h2MemoryUrl(s"schema-${java.util.UUID.randomUUID()}"))
    try
      val applied = database.withConnection { connection =>
        Using.resource(connection.createStatement()) { statement =>
          val rows = statement.executeQuery(
            """SELECT "script" FROM "flyway_schema_history" WHERE "type" = 'SQL' AND "success" ORDER BY "installed_rank""""
          )
          Iterator.continually(rows).takeWhile(_.next()).map(_.getString(1)).toList
        }
      }

      applied shouldBe Schema.MigrationFiles
    finally database.close()
  }
