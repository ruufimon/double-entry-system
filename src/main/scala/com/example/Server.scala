package com.example

import java.nio.charset.StandardCharsets
import java.time.{Clock, Duration, Instant, ZoneId}
import java.util.UUID

import com.example.banking.application.{
  AccountMessageHandler,
  AccountService,
  AdjustmentService,
  AuditLogService,
  DepositService,
  InterestRateService,
  MonthlyInterestScheduler,
  TransferService,
  WithdrawService
}
import com.example.banking.http.{AdminServlet, BankingServlet}
import com.example.banking.infrastructure.{
  H2AccountRepository,
  H2AuditLogRepository,
  H2InterestSettingsRepository,
  LocalMessageBus
}
import com.example.banking.domain.{AccountId, Money}
import com.example.banking.ledger.{LedgerBackedAccountOperations, LedgerError}
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
    private val database: Database,
    private val interestScheduler: MonthlyInterestScheduler
) extends AutoCloseable:
  def start(): Unit =
    server.start()
    interestScheduler.start()

  def join(): Unit = server.join()

  def port: Int =
    server.getConnectors.collectFirst { case connector: NetworkConnector =>
      connector.getLocalPort
    }.getOrElse(throw new IllegalStateException("API server has no network connector"))

  override def close(): Unit =
    try
      try interestScheduler.close()
      finally
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
    create(
      port,
      billerUrl = sys.env.get("BILLER_URL").filter(_.nonEmpty),
      adminToken = sys.env.get("ADMIN_TOKEN").filter(_.nonEmpty),
      adminPassword = sys.env.get("ADMIN_PASSWORD"),
      clock = Clock.systemUTC(),
      businessZone = ZoneId.of("Asia/Bangkok")
    )

  /** Uses the fake biller over HTTP when `billerUrl` is set, otherwise an in-process biller.
    * Admin balance adjustments are disabled unless `adminToken` is set.
    * Admin account login requires `adminPassword`.
    */
  private[example] def create(
      port: Int,
      billerUrl: Option[String],
      adminToken: Option[String],
      adminPassword: Option[String],
      clock: Clock,
      businessZone: ZoneId
  ): BankingApiServer =
    val server = new JettyServer(port)
    val context = new ServletContextHandler()
    val (dbUrl, dbUser, dbPassword) = databaseFromEnv()
    val poolSize = Database.poolSizeFrom(sys.env.get("DB_POOL_SIZE")).fold(
      error => throw new IllegalStateException(error),
      identity
    )
    val database = Database.open(dbUrl, dbUser, dbPassword, poolSize)
    val ledgerRepository = new H2LedgerRepository(database)
    val accountRepository = new H2AccountRepository(database)
    val accountOperations = new LedgerBackedAccountOperations(
      ledgerRepository,
      accountRepository
    )
    val interestSettingsRepository = new H2InterestSettingsRepository(database)
    val interestRateService = new InterestRateService(
      ledgerRepository,
      accountRepository,
      interestSettingsRepository,
      clock,
      businessZone
    )
    val interestScheduler = new MonthlyInterestScheduler(interestRateService, clock, businessZone)
    seedDemoAccounts(accountOperations)
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
    // Development mode, the default, renders stack traces and route listings.
    context.setInitParameter("org.scalatra.environment", "production")
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
      new ServletHolder(new AdminServlet(accountOperations, adjustmentService, Some(interestRateService), adminToken, adminPassword)),
      "/admin/*"
    )
    server.setHandler(context)
    new BankingApiServer(server, messageBus, database, interestScheduler)

  private def seedDemoAccounts(accountOperations: LedgerBackedAccountOperations): Unit =
    Vector("acc123", "acc456").foreach { rawAccountId =>
      val accountId = AccountId.from(rawAccountId).fold(
        error => throw new IllegalStateException(error.message),
        identity
      )
      accountOperations.open(accountId).fold(
        error => throw new IllegalStateException(error.message),
        _ => ()
      )
      val seedTransactionId = UUID.nameUUIDFromBytes(
        s"demo-account-opening:$rawAccountId".getBytes(StandardCharsets.UTF_8)
      )
      accountOperations.deposit(
        seedTransactionId,
        accountId,
        Money.of(BigDecimal("100.00")),
        Instant.now()
      ) match
        case Right(_) => ()
        case Left(LedgerError.DuplicateTransaction(_)) => ()
        case Left(error) => throw new IllegalStateException(error.message)
    }
