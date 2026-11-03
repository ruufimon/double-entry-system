package com.example.http

import org.scalatra.{InternalServerError, NotFound, ScalatraServlet}
import org.slf4j.LoggerFactory

/** Replaces Scalatra's default error and not-found pages with fixed JSON bodies.
  *
  * Without this, an uncaught exception returns its stack trace, and an unknown path lists every
  * route the servlet defines. Details are logged on the server instead.
  */
trait JsonErrors:
  self: ScalatraServlet =>

  private val errorLog = LoggerFactory.getLogger(getClass)

  error { case failure: Throwable =>
    errorLog.error(s"Unhandled error on ${request.getMethod} ${request.getRequestURI}", failure)
    contentType = "application/json"
    InternalServerError(JsonErrors.InternalErrorBody)
  }

  notFound {
    contentType = "application/json"
    NotFound(JsonErrors.NotFoundBody)
  }

object JsonErrors:
  val InternalErrorBody: String =
    """{"error":"internal_error","message":"An unexpected error occurred"}"""
  val NotFoundBody: String =
    """{"error":"not_found","message":"No such route"}"""
