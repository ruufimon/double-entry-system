package com.example.biller

import java.time.Duration

import org.eclipse.jetty.ee11.servlet.{ServletContextHandler, ServletHolder}
import org.eclipse.jetty.server.{NetworkConnector, Server as JettyServer}

final class FakeBillerServer(private val server: JettyServer) extends AutoCloseable:
  def start(): Unit = server.start()

  def join(): Unit = server.join()

  def port: Int =
    server.getConnectors.collectFirst { case connector: NetworkConnector =>
      connector.getLocalPort
    }.getOrElse(throw new IllegalStateException("Biller server has no network connector"))

  override def close(): Unit = server.stop()

/** Standalone fake biller that the banking API reaches over HTTP via BILLER_URL. */
object FakeBillerServer:
  def main(args: Array[String]): Unit =
    val port = sys.env.get("BILLER_PORT").flatMap(_.toIntOption).getOrElse(8090)
    val latencyMs = sys.env.get("BILLER_LATENCY_MS").flatMap(_.toLongOption).getOrElse(0L)
    val server = create(port, latencyMs)

    try
      server.start()
      println(s"Fake biller listening on http://localhost:${server.port}")
      server.join()
    finally server.close()

  def create(
      port: Int,
      latencyMs: Long = 0L,
      settleTimeoutDelay: Duration = Duration.ofSeconds(30)
  ): FakeBillerServer =
    val server = new JettyServer(port)
    val context = new ServletContextHandler()
    val store = new FakeBillerStore(FakeBillerStore.DefaultSeed, latencyMs)
    context.setContextPath("/")
    context.addServlet(
      new ServletHolder(new FakeBillerServlet(store, settleTimeoutDelay)),
      "/*"
    )
    server.setHandler(context)
    new FakeBillerServer(server)
