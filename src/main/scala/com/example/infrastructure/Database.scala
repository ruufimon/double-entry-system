package com.example.infrastructure

import java.net.URI
import java.sql.Connection

import scala.util.Using

import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway

object Database:
  def h2Url(path: String): String =
    s"jdbc:h2:file:$path;DB_CLOSE_DELAY=-1"

  def h2MemoryUrl(name: String): String =
    s"jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1"

  def postgresUrl(host: String, port: Int, database: String): String =
    s"jdbc:postgresql://$host:$port/$database"

  def parsePostgresUrl(url: String): Option[(String, Int, String, String, String)] =
    if !url.startsWith("postgresql://") && !url.startsWith("postgres://") then None
    else
      try
        val uri = new URI(url.stripPrefix("postgres://").replace("postgres://", "postgresql://"))
        val userInfo = Option(uri.getUserInfo).map(_.split(":", 2))
        val user = userInfo.flatMap(_.headOption)
        val password = userInfo.flatMap(_.lift(1))
        (user, password, Option(uri.getHost), Option(uri.getPort), Option(uri.getPath)) match
          case (Some(u), Some(p), Some(h), Some(port), Some(path)) if path.nonEmpty =>
            Some((h, port, path.stripPrefix("/"), u, p))
          case _ => None
      catch case _: Exception => None

  def open(url: String, user: String = DatabaseConfig.DefaultUser, password: String = DatabaseConfig.DefaultPassword): Database =
    Schema.migrate(url, user, password)
    new Database(url, user, password)

object DatabaseConfig:
  val DefaultUser = "banking"
  val DefaultPassword = "banking"

object Schema:
  def migrate(url: String, user: String, password: String): Unit =
    val flyway = Flyway.configure()
      .dataSource(url, user, password)
      .locations("classpath:db/migration")
      .load()
    flyway.migrate()

final class Database(
    val url: String,
    val user: String = DatabaseConfig.DefaultUser,
    val password: String = DatabaseConfig.DefaultPassword
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
