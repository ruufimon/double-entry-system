package com.example

import java.time.Clock
import java.util.UUID

import com.example.banking.application.{
  AccountMessageHandler,
  AccountService,
  AuditLogService,
  DepositService,
  WithdrawService
}
import com.example.banking.http.{AdminServlet, BankingServlet}
import com.example.banking.infrastructure.{
  InMemoryAccountRepository,
  InMemoryAuditLogRepository,
  LocalMessageBus
}
import com.example.banking.ledger.{LedgerBackedAccountOperations}
import com.example.banking.ledger.infrastructure.InMemoryLedgerRepository
import com.example.billpayment.application.{BillPaymentProcessManager, BillPaymentService}
import com.example.billpayment.infrastructure.{
  BillSeed,
  InMemoryBillerGateway,
  InMemoryBillPaymentInquiryRepository,
  InMemoryBillPaymentProcessRepository
}
import org.eclipse.jetty.ee11.servlet.{ServletContextHandler, ServletHolder}
import org.eclipse.jetty.server.{NetworkConnector, Server as JettyServer}

private[example] final class BankingApiServer(
    private val server: JettyServer,
    private val messageBus: LocalMessageBus
) extends AutoCloseable:
  def start(): Unit = server.start()

  def join(): Unit = server.join()

  def port: Int =
    server.getConnectors.collectFirst { case connector: NetworkConnector =>
      connector.getLocalPort
    }.getOrElse(throw new IllegalStateException("API server has no network connector"))

  override def close(): Unit =
    try server.stop()
    finally messageBus.close()

object Server:
  def main(args: Array[String]): Unit =
    val port = sys.env.get("PORT").flatMap(_.toIntOption).getOrElse(8080)
    val server = create(port)

    try
      server.start()
      println(s"API listening on http://localhost:${server.port}")
      server.join()
    finally server.close()

  private[example] def create(port: Int): BankingApiServer =
    val server = new JettyServer(port)
    val context = new ServletContextHandler()
    val ledgerRepository = new InMemoryLedgerRepository()
    val accountRepository = new InMemoryAccountRepository()
    val accountOperations = new LedgerBackedAccountOperations(
      ledgerRepository,
      accountRepository
    )
    val accountService = new AccountService(accountOperations)
    val auditLogRepository = new InMemoryAuditLogRepository()
    val inquiryRepository = new InMemoryBillPaymentInquiryRepository()
    val processRepository = new InMemoryBillPaymentProcessRepository()
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
    new BankingApiServer(server, messageBus)
