package com.example.infrastructure

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class DatabaseSelectionSpec extends AnyFunSuite with Matchers:

  test("PG* environment variables select PostgreSQL") {
    val result = Database.selectDatabase(
      pgHost = Some("localhost"),
      pgPort = Some("5432"),
      pgDatabase = Some("banking"),
      pgUser = Some("postgres"),
      pgPassword = Some("secret"),
      databaseUrl = None,
      dbUrl = None,
      dbUser = None,
      dbPassword = None,
      railwayEnvironment = None,
      requirePostgres = false
    )

    result shouldBe a[Right[?, ?]]
    val config = result.toOption.get
    config.vendor shouldBe DatabaseVendor.PostgreSQL
    config.jdbcUrl shouldBe "jdbc:postgresql://localhost:5432/banking"
    config.user shouldBe "postgres"
    config.password shouldBe "secret"
    config.host shouldBe Some("localhost")
    config.database shouldBe Some("banking")
  }

  test("DATABASE_URL selects PostgreSQL with correct JDBC URL") {
    val result = Database.selectDatabase(
      pgHost = None,
      pgPort = None,
      pgDatabase = None,
      pgUser = None,
      pgPassword = None,
      databaseUrl = Some("postgresql://dbuser:dbpass@db.example.com:5433/mydb"),
      dbUrl = None,
      dbUser = None,
      dbPassword = None,
      railwayEnvironment = None,
      requirePostgres = false
    )

    result shouldBe a[Right[?, ?]]
    val config = result.toOption.get
    config.vendor shouldBe DatabaseVendor.PostgreSQL
    config.jdbcUrl shouldBe "jdbc:postgresql://db.example.com:5433/mydb"
    config.user shouldBe "dbuser"
    config.password shouldBe "dbpass"
    config.host shouldBe Some("db.example.com")
    config.database shouldBe Some("mydb")
  }

  test("postgres:// scheme in DATABASE_URL is handled correctly") {
    val result = Database.selectDatabase(
      pgHost = None,
      pgPort = None,
      pgDatabase = None,
      pgUser = None,
      pgPassword = None,
      databaseUrl = Some("postgres://user:pass@host:5432/db"),
      dbUrl = None,
      dbUser = None,
      dbPassword = None,
      railwayEnvironment = None,
      requirePostgres = false
    )

    result shouldBe a[Right[?, ?]]
    val config = result.toOption.get
    config.vendor shouldBe DatabaseVendor.PostgreSQL
    config.jdbcUrl shouldBe "jdbc:postgresql://host:5432/db"
  }

  test("PG* variables take precedence over DATABASE_URL") {
    val result = Database.selectDatabase(
      pgHost = Some("primary-host"),
      pgPort = Some("5432"),
      pgDatabase = Some("primary-db"),
      pgUser = Some("primary-user"),
      pgPassword = Some("primary-pass"),
      databaseUrl = Some("postgresql://fallback:pass@fallback-host:5432/fallback-db"),
      dbUrl = None,
      dbUser = None,
      dbPassword = None,
      railwayEnvironment = None,
      requirePostgres = false
    )

    result shouldBe a[Right[?, ?]]
    val config = result.toOption.get
    config.host shouldBe Some("primary-host")
    config.database shouldBe Some("primary-db")
    config.user shouldBe "primary-user"
  }

  test("nothing set defaults to H2 in-memory") {
    val result = Database.selectDatabase(
      pgHost = None,
      pgPort = None,
      pgDatabase = None,
      pgUser = None,
      pgPassword = None,
      databaseUrl = None,
      dbUrl = None,
      dbUser = None,
      dbPassword = None,
      railwayEnvironment = None,
      requirePostgres = false
    )

    result shouldBe a[Right[?, ?]]
    val config = result.toOption.get
    config.vendor shouldBe DatabaseVendor.H2
    config.jdbcUrl should startWith("jdbc:h2:mem:banking-")
    config.user shouldBe "banking"
    config.password shouldBe "banking"
  }

  test("DB_URL selects H2 file-based when no PostgreSQL config") {
    val result = Database.selectDatabase(
      pgHost = None,
      pgPort = None,
      pgDatabase = None,
      pgUser = None,
      pgPassword = None,
      databaseUrl = None,
      dbUrl = Some("/data/banking"),
      dbUser = Some("h2user"),
      dbPassword = Some("h2pass"),
      railwayEnvironment = None,
      requirePostgres = false
    )

    result shouldBe a[Right[?, ?]]
    val config = result.toOption.get
    config.vendor shouldBe DatabaseVendor.H2
    config.jdbcUrl shouldBe "/data/banking" // DB_URL is used as-is
    config.user shouldBe "h2user"
    config.password shouldBe "h2pass"
  }

  test("RAILWAY_ENVIRONMENT set without PostgreSQL config returns error") {
    val result = Database.selectDatabase(
      pgHost = None,
      pgPort = None,
      pgDatabase = None,
      pgUser = None,
      pgPassword = None,
      databaseUrl = None,
      dbUrl = None,
      dbUser = None,
      dbPassword = None,
      railwayEnvironment = Some("production"),
      requirePostgres = false
    )

    result shouldBe a[Left[?, ?]]
    result.left.toOption.get shouldBe DatabaseSelectionError.PostgresRequiredOnRailway("production")
  }

  test("RAILWAY_ENVIRONMENT with PostgreSQL config succeeds") {
    val result = Database.selectDatabase(
      pgHost = Some("railway-db"),
      pgPort = Some("5432"),
      pgDatabase = Some("railway"),
      pgUser = Some("railway-user"),
      pgPassword = Some("railway-pass"),
      databaseUrl = None,
      dbUrl = None,
      dbUser = None,
      dbPassword = None,
      railwayEnvironment = Some("production"),
      requirePostgres = false
    )

    result shouldBe a[Right[?, ?]]
    val config = result.toOption.get
    config.vendor shouldBe DatabaseVendor.PostgreSQL
  }

  test("DB_REQUIRE_POSTGRES=true without PostgreSQL config returns error") {
    val result = Database.selectDatabase(
      pgHost = None,
      pgPort = None,
      pgDatabase = None,
      pgUser = None,
      pgPassword = None,
      databaseUrl = None,
      dbUrl = None,
      dbUser = None,
      dbPassword = None,
      railwayEnvironment = None,
      requirePostgres = true
    )

    result shouldBe a[Left[?, ?]]
    result.left.toOption.get shouldBe DatabaseSelectionError.PostgresRequiredByConfig
  }

  test("DB_REQUIRE_POSTGRES=true with PostgreSQL config succeeds") {
    val result = Database.selectDatabase(
      pgHost = None,
      pgPort = None,
      pgDatabase = None,
      pgUser = None,
      pgPassword = None,
      databaseUrl = Some("postgresql://user:pass@host:5432/db"),
      dbUrl = None,
      dbUser = None,
      dbPassword = None,
      railwayEnvironment = None,
      requirePostgres = true
    )

    result shouldBe a[Right[?, ?]]
    val config = result.toOption.get
    config.vendor shouldBe DatabaseVendor.PostgreSQL
  }

  test("incomplete PG* variables fall back to DATABASE_URL") {
    val result = Database.selectDatabase(
      pgHost = Some("host"),
      pgPort = Some("5432"),
      pgDatabase = None, // missing
      pgUser = Some("user"),
      pgPassword = Some("pass"),
      databaseUrl = Some("postgresql://user:pass@fallback:5432/db"),
      dbUrl = None,
      dbUser = None,
      dbPassword = None,
      railwayEnvironment = None,
      requirePostgres = false
    )

    result shouldBe a[Right[?, ?]]
    val config = result.toOption.get
    config.host shouldBe Some("fallback")
    config.database shouldBe Some("db")
  }

  test("invalid PGPORT is ignored") {
    val result = Database.selectDatabase(
      pgHost = Some("host"),
      pgPort = Some("not-a-number"),
      pgDatabase = Some("db"),
      pgUser = Some("user"),
      pgPassword = Some("pass"),
      databaseUrl = None,
      dbUrl = None,
      dbUser = None,
      dbPassword = None,
      railwayEnvironment = None,
      requirePostgres = false
    )

    result shouldBe a[Right[?, ?]]
    val config = result.toOption.get
    config.vendor shouldBe DatabaseVendor.H2 // falls back because port is invalid
  }

  test("malformed DATABASE_URL falls back to H2") {
    val result = Database.selectDatabase(
      pgHost = None,
      pgPort = None,
      pgDatabase = None,
      pgUser = None,
      pgPassword = None,
      databaseUrl = Some("not-a-valid-url"),
      dbUrl = None,
      dbUser = None,
      dbPassword = None,
      railwayEnvironment = None,
      requirePostgres = false
    )

    result shouldBe a[Right[?, ?]]
    val config = result.toOption.get
    config.vendor shouldBe DatabaseVendor.H2
  }

  test("DATABASE_URL without credentials is rejected") {
    val result = Database.selectDatabase(
      pgHost = None,
      pgPort = None,
      pgDatabase = None,
      pgUser = None,
      pgPassword = None,
      databaseUrl = Some("postgresql://host:5432/db"),
      dbUrl = None,
      dbUser = None,
      dbPassword = None,
      railwayEnvironment = None,
      requirePostgres = false
    )

    result shouldBe a[Right[?, ?]]
    val config = result.toOption.get
    config.vendor shouldBe DatabaseVendor.H2 // falls back because credentials missing
  }

  test("DATABASE_URL query parameter is kept in the JDBC URL") {
    val config = configFromDatabaseUrl("postgresql://user:pass@db.example.com:25060/defaultdb?sslmode=require")

    config.vendor shouldBe DatabaseVendor.PostgreSQL
    config.jdbcUrl shouldBe "jdbc:postgresql://db.example.com:25060/defaultdb?sslmode=require"
    config.database shouldBe Some("defaultdb")
  }

  test("multiple DATABASE_URL query parameters are kept verbatim and in order") {
    val config = configFromDatabaseUrl(
      "postgresql://user:pass@host:5432/db?sslmode=require&application_name=my%20app&connectTimeout=10"
    )

    config.jdbcUrl shouldBe
      "jdbc:postgresql://host:5432/db?sslmode=require&application_name=my%20app&connectTimeout=10"
    config.database shouldBe Some("db")
  }

  test("postgres:// scheme keeps its query parameters unchanged") {
    val config = configFromDatabaseUrl(
      "postgres://user:pass@host:5432/db?sslmode=require&application_name=postgres://app"
    )

    config.jdbcUrl shouldBe
      "jdbc:postgresql://host:5432/db?sslmode=require&application_name=postgres://app"
  }

  test("DATABASE_URL without a query produces a JDBC URL without a question mark") {
    configFromDatabaseUrl("postgresql://user:pass@host:5432/db").jdbcUrl shouldBe
      "jdbc:postgresql://host:5432/db"
    configFromDatabaseUrl("postgresql://user:pass@host:5432/db?").jdbcUrl shouldBe
      "jdbc:postgresql://host:5432/db"
  }

  test("URL-encoded password is decoded while the query stays in the JDBC URL") {
    val config = configFromDatabaseUrl("postgresql://user:p%40ss%2Fw%3Ard@host:5432/db?sslmode=require")

    config.user shouldBe "user"
    config.password shouldBe "p@ss/w:rd"
    config.jdbcUrl shouldBe "jdbc:postgresql://host:5432/db?sslmode=require"
  }

  test("DATABASE_URL without a port defaults to 5432") {
    val config = configFromDatabaseUrl("postgresql://user:pass@db.example.com/mydb")

    config.vendor shouldBe DatabaseVendor.PostgreSQL
    config.jdbcUrl shouldBe "jdbc:postgresql://db.example.com:5432/mydb"
    config.host shouldBe Some("db.example.com")
    config.database shouldBe Some("mydb")
    configFromDatabaseUrl("postgres://user:pass@host/db?sslmode=require").jdbcUrl shouldBe
      "jdbc:postgresql://host:5432/db?sslmode=require"
  }

  private def configFromDatabaseUrl(databaseUrl: String): DatabaseConfig =
    Database.selectDatabase(
      pgHost = None,
      pgPort = None,
      pgDatabase = None,
      pgUser = None,
      pgPassword = None,
      databaseUrl = Some(databaseUrl),
      dbUrl = None,
      dbUser = None,
      dbPassword = None,
      railwayEnvironment = None,
      requirePostgres = false
    ) match
      case Right(config) => config
      case Left(error)   => fail(s"Expected Right, got $error")
