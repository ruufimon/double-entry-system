package com.example

import java.time.Clock
import java.util.UUID

import com.example.banking.application.{AuditLogService, DepositService, WithdrawService}
import com.example.banking.http.BankingServlet
import com.example.banking.infrastructure.{
  InMemoryAccountRepository,
  InMemoryAuditLogRepository,
  LocalMessageBus
}
import org.eclipse.jetty.ee11.servlet.{ServletContextHandler, ServletHolder}
import org.eclipse.jetty.server.Server as JettyServer

object Server:
  def main(args: Array[String]): Unit =
    val port = sys.env.get("PORT").flatMap(_.toIntOption).getOrElse(8080)
    val server = new JettyServer(port)
    val context = new ServletContextHandler()
    val accountRepository = new InMemoryAccountRepository()
    val auditLogRepository = new InMemoryAuditLogRepository()
    val messageBus = new LocalMessageBus()
    val auditLogService = new AuditLogService(messageBus, auditLogRepository)
    val depositService = new DepositService(
      accountRepository,
      messageBus,
      Clock.systemUTC(),
      () => UUID.randomUUID()
    )
    val withdrawService = new WithdrawService(
      accountRepository,
      messageBus,
      Clock.systemUTC(),
      () => UUID.randomUUID()
    )
    auditLogService.subscribe()

    context.setContextPath("/")
    context.addServlet(new ServletHolder(new PingServlet()), "/*")
    context.addServlet(
      new ServletHolder(new BankingServlet(depositService, withdrawService)),
      "/accounts/*"
    )
    server.setHandler(context)

    server.start()
    println(s"API listening on http://localhost:$port")
    server.join()
