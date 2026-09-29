ThisBuild / organization := "com.example"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.3.8"

lazy val scalatraVersion = "3.2.1"
lazy val jettyVersion = "12.1.13"

lazy val root = (project in file("."))
  .settings(
    name := "scalatra-ping-api",
    libraryDependencies ++= Seq(
      "org.scalatra" %% "scalatra-jakarta" % scalatraVersion,
      "org.scalatra" %% "scalatra-json-jakarta" % scalatraVersion,
      "io.github.json4s" %% "json4s-jackson" % "4.1.1",
      "org.eclipse.jetty.ee11" % "jetty-ee11-servlet" % jettyVersion,
      "org.slf4j" % "slf4j-simple" % "2.0.20" % Runtime,
      "org.scalatra" %% "scalatra-scalatest-jakarta" % scalatraVersion % Test
    )
  )
