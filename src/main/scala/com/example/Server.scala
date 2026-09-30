package com.example

import java.time.Clock
import java.util.UUID

import com.example.banking.application.{
  AccountMessageHandler,
  AuditLogService,
  DepositService,
  WithdrawService
}
import com.example.banking.http.BankingServlet
import com.example.banking.infrastructure.{
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
import org.eclipse.jetty.server.Server as JettyServer

object Server:
  def main(args: Array[String]): Unit =
    val port = sys.env.get("PORT").flatMap(_.toIntOption).getOrElse(8080)
    val server = new JettyServer(port)
    val context = new ServletContextHandler()
    val ledgerRepository = new InMemoryLedgerRepository()
    val accountOperations = new LedgerBackedAccountOperations(ledgerRepository)
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
          depositService,
          withdrawService,
          billPaymentService,
          accountOperations
        )
      ),
      "/accounts/*"
    )
    server.setHandler(context)

    try
      server.start()
      println(s"API listening on http://localhost:$port")
      server.join()
    finally messageBus.close()
