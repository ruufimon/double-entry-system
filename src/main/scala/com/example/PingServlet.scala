package com.example

import org.scalatra.ScalatraServlet

final class PingServlet extends ScalatraServlet:
  get("/ping") :
    contentType = "text/plain"
    "pong"
  
