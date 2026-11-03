package com.example.infrastructure

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class DatabasePoolSizeSpec extends AnyFunSuite with Matchers:
  test("an unset or blank DB_POOL_SIZE uses the default") {
    Database.poolSizeFrom(None) shouldBe Right(Database.DefaultPoolSize)
    Database.poolSizeFrom(Some("  ")) shouldBe Right(Database.DefaultPoolSize)
  }

  test("a positive DB_POOL_SIZE is used") {
    Database.poolSizeFrom(Some("4")) shouldBe Right(4)
    Database.poolSizeFrom(Some(" 4 ")) shouldBe Right(4)
  }

  test("a zero, negative or non-numeric DB_POOL_SIZE is rejected") {
    Seq("0", "-1", "four", "4.5").foreach { raw =>
      Database.poolSizeFrom(Some(raw)).isLeft shouldBe true
    }
  }

  test("the pool size reaches the connection pool") {
    val database = Database.open(Database.h2MemoryUrl(s"pool-${java.util.UUID.randomUUID()}"), maxPoolSize = 3)
    try database.dataSource.getMaximumPoolSize shouldBe 3
    finally database.close()
  }
