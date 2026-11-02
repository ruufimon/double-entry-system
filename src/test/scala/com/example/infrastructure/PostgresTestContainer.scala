package com.example.infrastructure

import org.scalatest.{BeforeAndAfterAll, Suite}
import org.testcontainers.containers.PostgreSQLContainer

trait PostgresTestContainer extends BeforeAndAfterAll:
  this: Suite =>

  private val postgresContainer: PostgreSQLContainer[?] =
    val container = new PostgreSQLContainer("postgres:17-alpine")
    container.withDatabaseName("banking_test")
    container.withUsername("test_user")
    container.withPassword("test_password")
    container

  protected def createPostgresDatabase(): Database =
    Database.open(
      postgresContainer.getJdbcUrl,
      postgresContainer.getUsername,
      postgresContainer.getPassword
    )

  override def beforeAll(): Unit =
    super.beforeAll()
    postgresContainer.start()

  override def afterAll(): Unit =
    try super.afterAll()
    finally postgresContainer.stop()
