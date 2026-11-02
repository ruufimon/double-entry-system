package com.example.infrastructure

import org.flywaydb.core.Flyway
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Suite}
import org.testcontainers.containers.PostgreSQLContainer

trait PostgresTestContainer extends BeforeAndAfterAll with BeforeAndAfterEach:
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

  /** Gives each test an empty, migrated database, since the container is shared across the suite. */
  override def beforeEach(): Unit =
    super.beforeEach()
    cleanPostgresDatabase()
    createPostgresDatabase().close()

  // Clean is enabled here only; production Schema.migrate keeps Flyway's default of cleanDisabled=true.
  private def cleanPostgresDatabase(): Unit =
    Flyway.configure()
      .dataSource(
        postgresContainer.getJdbcUrl,
        postgresContainer.getUsername,
        postgresContainer.getPassword
      )
      .cleanDisabled(false)
      .load()
      .clean()

  override def afterAll(): Unit =
    try super.afterAll()
    finally postgresContainer.stop()
