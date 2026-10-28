package com.example

import java.nio.charset.StandardCharsets
import java.time.{Clock, Instant}
import java.util.UUID

import com.example.banking.application.{
  AccountMessageHandler,
  AccountService,
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
import com.example.banking.domain.{AccountId, Money}
import com.example.banking.ledger.{LedgerBackedAccountOperations, LedgerError}
import com.example.banking.ledger.infrastructure.H2LedgerRepository
import com.example.billpayment.application.{BillPaymentProcessManager, BillPaymentService}
import com.example.billpayment.infrastructure.{
  BillSeed,
  H2BillPaymentInquiryRepository,
  H2BillPaymentProcessRepository,
  InMemoryBillerGateway
}
import com.example.infrastructure.{Database, DatabaseConfig}
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

  /** Reads H2 connection configuration from the process environment. */
  def databaseFromEnv(): (String, String, String) =
    (
      // Without DB_URL each server start gets an isolated in-memory DB. Set DB_URL
      // (a file path) to persist data across restarts.
      sys.env
        .get("DB_URL")
        .getOrElse(Database.memoryUrl(s"banking-${UUID.randomUUID()}")),
      sys.env.get("DB_USER").getOrElse(DatabaseConfig.DefaultUser),
      sys.env.get("DB_PASSWORD").getOrElse(DatabaseConfig.DefaultPassword)
    )

  private[example] def create(port: Int): BankingApiServer =
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
    seedDemoAccounts(accountOperations)
    val accountService = new AccountService(accountOperations)
    val auditLogRepository = new H2AuditLogRepository(database)
    val inquiryRepository = new H2BillPaymentInquiryRepository(database)
    val processRepository = new H2BillPaymentProcessRepository(database)
    val billerGateway = new InMemoryBillerGateway(
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
      new ServletHolder(new AdminServlet(accountOperations)),
      "/admin/*"
    )
    server.setHandler(context)
    new BankingApiServer(server, messageBus, database)

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
