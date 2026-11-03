package com.example

import com.example.http.JsonErrors
import org.scalatra.ScalatraServlet

final class PingServlet extends ScalatraServlet with JsonErrors:
  get("/ping") :
    contentType = "text/plain"
    "pong"
  
