package com.example.infrastructure

import java.net.URI
import java.sql.Connection

import scala.util.Using

import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway

enum DatabaseVendor:
  case H2, PostgreSQL

final case class DatabaseConfig(
    vendor: DatabaseVendor,
    jdbcUrl: String,
    user: String,
    password: String,
    host: Option[String] = None,
    database: Option[String] = None
)

final case class ParsedPostgresUrl(
    host: String,
    port: Int,
    database: String,
    user: String,
    password: String,
    query: Option[String]
)

enum DatabaseSelectionError(val message: String):
  case PostgresRequiredOnRailway(railwayEnv: String)
      extends DatabaseSelectionError(
        s"PostgreSQL is required on Railway (RAILWAY_ENVIRONMENT=$railwayEnv) but no PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD or DATABASE_URL is set"
      )
  case PostgresRequiredByConfig
      extends DatabaseSelectionError(
        "PostgreSQL is required (DB_REQUIRE_POSTGRES=true) but no PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD or DATABASE_URL is set"
      )

object Database:
  private val DefaultPostgresPort = 5432

  def h2Url(path: String): String =
    s"jdbc:h2:file:$path;DB_CLOSE_DELAY=-1"

  def h2MemoryUrl(name: String): String =
    s"jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1"

  def postgresUrl(host: String, port: Int, database: String, query: Option[String] = None): String =
    s"jdbc:postgresql://$host:$port/$database${query.fold("")(parameters => s"?$parameters")}"

  def parsePostgresUrl(url: String): Option[ParsedPostgresUrl] =
    if !url.startsWith("postgresql://") && !url.startsWith("postgres://") then None
    else
      try
        val uri = new URI(url)
        val userInfo = Option(uri.getUserInfo).map(_.split(":", 2))
        val user = userInfo.flatMap(_.headOption)
        val password = userInfo.flatMap(_.lift(1))
        // The raw query keeps its percent-encoding, which pgjdbc decodes itself.
        val query = Option(uri.getRawQuery).filter(_.nonEmpty)
        // URI#getPort reports a missing port as -1.
        val port = if uri.getPort == -1 then DefaultPostgresPort else uri.getPort
        (user, password, Option(uri.getHost), Option(uri.getPath)) match
          case (Some(u), Some(p), Some(h), Some(path)) if path.nonEmpty =>
            Some(ParsedPostgresUrl(h, port, path.stripPrefix("/"), u, p, query))
          case _ => None
      catch case _: Exception => None

  /** Pure database selection logic that can be tested without side effects. */
  def selectDatabase(
      pgHost: Option[String],
      pgPort: Option[String],
      pgDatabase: Option[String],
      pgUser: Option[String],
      pgPassword: Option[String],
      databaseUrl: Option[String],
      dbUrl: Option[String],
      dbUser: Option[String],
      dbPassword: Option[String],
      railwayEnvironment: Option[String],
      requirePostgres: Boolean
  ): Either[DatabaseSelectionError, DatabaseConfig] =
    val defaultUser = "banking"
    val defaultPassword = "banking"

    // Try PG* environment variables first
    val pgConfig = for
      host <- pgHost
      portStr <- pgPort
      port <- portStr.toIntOption
      database <- pgDatabase
      user <- pgUser
      password <- pgPassword
    yield DatabaseConfig(
      vendor = DatabaseVendor.PostgreSQL,
      jdbcUrl = postgresUrl(host, port, database),
      user = user,
      password = password,
      host = Some(host),
      database = Some(database)
    )

    // Try DATABASE_URL as fallback
    val databaseUrlConfig = pgConfig.orElse {
      databaseUrl.flatMap { url =>
        parsePostgresUrl(url).map { parsed =>
          DatabaseConfig(
            vendor = DatabaseVendor.PostgreSQL,
            jdbcUrl = postgresUrl(parsed.host, parsed.port, parsed.database, parsed.query),
            user = parsed.user,
            password = parsed.password,
            host = Some(parsed.host),
            database = Some(parsed.database)
          )
        }
      }
    }

    // Check if PostgreSQL is required but not configured
    databaseUrlConfig match
      case Some(config) => Right(config)
      case None =>
        if railwayEnvironment.isDefined then
          Left(DatabaseSelectionError.PostgresRequiredOnRailway(railwayEnvironment.get))
        else if requirePostgres then
          Left(DatabaseSelectionError.PostgresRequiredByConfig)
        else
          // Fall back to H2
          val h2Url = dbUrl.getOrElse(h2MemoryUrl(s"banking-${java.util.UUID.randomUUID()}"))
          Right(
            DatabaseConfig(
              vendor = DatabaseVendor.H2,
              jdbcUrl = h2Url,
              user = dbUser.getOrElse(defaultUser),
              password = dbPassword.getOrElse(defaultPassword)
            )
          )

  def open(url: String, user: String = "banking", password: String = "banking"): Database =
    Schema.migrate(url, user, password)
    new Database(url, user, password)

object Schema:
  def migrate(url: String, user: String, password: String): Unit =
    val flyway = Flyway.configure()
      .dataSource(url, user, password)
      .locations("classpath:db/migration")
      .load()
    flyway.migrate()

final class Database(
    val url: String,
    val user: String = "banking",
    val password: String = "banking"
) extends AutoCloseable:
  val dataSource: HikariDataSource =
    val hikariDataSource = new HikariDataSource()
    hikariDataSource.setJdbcUrl(url)
    hikariDataSource.setUsername(user)
    hikariDataSource.setPassword(password)
    hikariDataSource.setMaximumPoolSize(10)
    hikariDataSource.setConnectionTimeout(5000)
    Using.resource(hikariDataSource.getConnection())(_ => ())
    hikariDataSource

  def withConnection[A](operation: Connection => A): A =
    Using.resource(dataSource.getConnection())(operation)

  def withTransaction[A](operation: Connection => A): A =
    val connection = dataSource.getConnection()
    connection.setAutoCommit(false)
    try
      val result = operation(connection)
      connection.commit()
      result
    catch
      case error: Exception =>
        connection.rollback()
        throw error
    finally
      connection.setAutoCommit(true)
      connection.close()

  override def close(): Unit = dataSource.close()
