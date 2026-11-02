package com.example

import java.time.{Clock, Duration}
import java.util.UUID

import com.example.banking.application.{
  AccountMessageHandler,
  AccountService,
  AdjustmentService,
  AuditLogService,
  DepositService,
  TransferService,
  WithdrawService
}
import com.example.banking.http.{AdminServlet, BankingServlet}
import com.example.banking.infrastructure.{
  H2AccountRepository,
  H2AuditLogRepository,
  LocalMessageBus
}
import com.example.banking.ledger.{LedgerBackedAccountOperations}
import com.example.banking.ledger.infrastructure.H2LedgerRepository
import com.example.billpayment.application.{BillPaymentProcessManager, BillPaymentService}
import com.example.billpayment.infrastructure.{
  BillSeed,
  H2BillPaymentInquiryRepository,
  H2BillPaymentProcessRepository,
  HttpBillerGateway,
  InMemoryBillerGateway
}
import com.example.billpayment.ports.BillerGateway
import com.example.infrastructure.{Database, DatabaseConfig, DatabaseVendor}
import org.eclipse.jetty.ee11.servlet.{ServletContextHandler, ServletHolder}
import org.eclipse.jetty.server.{NetworkConnector, Server as JettyServer}

private[example] final class BankingApiServer(
    private val server: JettyServer,
    private val messageBus: LocalMessageBus,
    private val database: Database
) extends AutoCloseable:
  def start(): Unit = server.start()

  def join(): Unit = server.join()

  def port: Int =
    server.getConnectors.collectFirst { case connector: NetworkConnector =>
      connector.getLocalPort
    }.getOrElse(throw new IllegalStateException("API server has no network connector"))

  override def close(): Unit =
    try
      try server.stop()
      finally messageBus.close()
    finally database.close()

object Server:
  def main(args: Array[String]): Unit =
    val port = sys.env.get("PORT").flatMap(_.toIntOption).getOrElse(8080)
    val server = create(port)

    try
      server.start()
      println(s"API listening on http://localhost:${server.port}")
      server.join()
    finally server.close()

  /** Reads database configuration from the process environment.
    * Prefers PostgreSQL when PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD are set.
    * Falls back to DATABASE_URL in postgresql:// format.
    * On Railway (RAILWAY_ENVIRONMENT set), PostgreSQL is required and H2 fallback is disabled.
    * Set DB_REQUIRE_POSTGRES=true to enforce PostgreSQL in any environment.
    * Otherwise uses H2 in-memory (or file-based if DB_URL is set).
    */
  def databaseFromEnv(): (String, String, String) =
    val requirePostgres = sys.env.get("DB_REQUIRE_POSTGRES").contains("true")
    
    val config = Database.selectDatabase(
      pgHost = sys.env.get("PGHOST"),
      pgPort = sys.env.get("PGPORT"),
      pgDatabase = sys.env.get("PGDATABASE"),
      pgUser = sys.env.get("PGUSER"),
      pgPassword = sys.env.get("PGPASSWORD"),
      databaseUrl = sys.env.get("DATABASE_URL"),
      dbUrl = sys.env.get("DB_URL"),
      dbUser = sys.env.get("DB_USER"),
      dbPassword = sys.env.get("DB_PASSWORD"),
      railwayEnvironment = sys.env.get("RAILWAY_ENVIRONMENT"),
      requirePostgres = requirePostgres
    ) match
      case Right(cfg) =>
        logDatabaseSelection(cfg)
        cfg
      case Left(error) =>
        System.err.println(s"Database configuration error: ${error.message}")
        throw new IllegalStateException(error.message)
    
    (config.jdbcUrl, config.user, config.password)

  private def logDatabaseSelection(config: DatabaseConfig): Unit =
    config.vendor match
      case DatabaseVendor.PostgreSQL =>
        val hostInfo = config.host.map(h => s"$h/${config.database.getOrElse("?")}").getOrElse("?")
        println(s"Database: PostgreSQL at $hostInfo (user: ${config.user})")
      case DatabaseVendor.H2 =>
        val dbType = if config.jdbcUrl.contains(":mem:") then "in-memory" else "file-based"
        val dbName = config.jdbcUrl.split(":").lastOption.getOrElse("?").takeWhile(_ != ';')
        println(s"Database: H2 $dbType ($dbName)")

  private[example] def create(port: Int): BankingApiServer =
    create(port, sys.env.get("BILLER_URL").filter(_.nonEmpty))

  private[example] def create(port: Int, billerUrl: Option[String]): BankingApiServer =
    create(port, billerUrl, sys.env.get("ADMIN_TOKEN").filter(_.nonEmpty))

  /** Uses the fake biller over HTTP when `billerUrl` is set, otherwise an in-process biller.
    * Admin balance adjustments are disabled unless `adminToken` is set.
    */
  private[example] def create(
      port: Int,
      billerUrl: Option[String],
      adminToken: Option[String]
  ): BankingApiServer =
    val server = new JettyServer(port)
    val context = new ServletContextHandler()
    val (dbUrl, dbUser, dbPassword) = databaseFromEnv()
    val database = Database.open(dbUrl, dbUser, dbPassword)
    val ledgerRepository = new H2LedgerRepository(database)
    val accountRepository = new H2AccountRepository(database)
    val accountOperations = new LedgerBackedAccountOperations(
      ledgerRepository,
      accountRepository
    )
    val accountService = new AccountService(accountOperations)
    val auditLogRepository = new H2AuditLogRepository(database)
    val inquiryRepository = new H2BillPaymentInquiryRepository(database)
    val processRepository = new H2BillPaymentProcessRepository(database)
    val billerGateway: BillerGateway = billerUrl match
      case Some(url) =>
        val timeoutMs = sys.env.get("BILLER_TIMEOUT_MS").flatMap(_.toLongOption).getOrElse(5000L)
        new HttpBillerGateway(url, Duration.ofMillis(timeoutMs))
      case None =>
        new InMemoryBillerGateway(
          Vector(BillSeed("demo-biller", "customer-001", "invoice-001", BigDecimal("100.00")))
        )
    val messageBus = new LocalMessageBus()
    val auditLogService = new AuditLogService(messageBus, auditLogRepository)
    val accountMessageHandler = new AccountMessageHandler(
      accountOperations,
      messageBus,
      Clock.systemUTC()
    )
    val depositService = new DepositService(
      accountOperations,
      messageBus,
      Clock.systemUTC(),
      () => UUID.randomUUID()
    )
    val withdrawService = new WithdrawService(
      accountOperations,
      messageBus,
      Clock.systemUTC(),
      () => UUID.randomUUID()
    )
    val transferService = new TransferService(
      accountOperations,
      messageBus,
      Clock.systemUTC(),
      () => UUID.randomUUID()
    )
    val adjustmentService = new AdjustmentService(
      accountOperations,
      messageBus,
      Clock.systemUTC()
    )
    val billPaymentService = new BillPaymentService(
      billerGateway,
      inquiryRepository,
      processRepository,
      messageBus,
      Clock.systemUTC(),
      () => UUID.randomUUID(),
      () => UUID.randomUUID()
    )
    val billPaymentProcessManager = new BillPaymentProcessManager(
      billerGateway,
      inquiryRepository,
      processRepository,
      messageBus,
      Clock.systemUTC()
    )
    accountMessageHandler.subscribe()
    billPaymentProcessManager.subscribe()
    auditLogService.subscribe()

    context.setContextPath("/")
    context.addServlet(new ServletHolder(new PingServlet()), "/*")
    context.addServlet(
      new ServletHolder(
        new BankingServlet(
          accountService,
          depositService,
          withdrawService,
          transferService,
          billPaymentService,
          accountOperations
        )
      ),
      "/accounts/*"
    )
    context.addServlet(
      new ServletHolder(new AdminServlet(accountOperations, adjustmentService, adminToken)),
      "/admin/*"
    )
    server.setHandler(context)
    new BankingApiServer(server, messageBus, database)
