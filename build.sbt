ThisBuild / organization := "com.example"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.3.8"

lazy val scalatraVersion = "3.2.1"
lazy val jettyVersion = "12.1.13"
lazy val catsVersion = "2.13.0"
lazy val h2Version = "2.3.232"
lazy val hikariVersion = "5.1.0"
lazy val postgresVersion = "42.7.4"
lazy val flywayVersion = "11.1.0"
lazy val testcontainersVersion = "1.20.4"

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "scalatra-ping-api",
    // The fake biller (com.example.biller.FakeBillerServer) is a second entry point.
    Compile / mainClass := Some("com.example.Server"),
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % catsVersion,
      "org.scalatra" %% "scalatra-jakarta" % scalatraVersion,
      "org.scalatra" %% "scalatra-json-jakarta" % scalatraVersion,
      "io.github.json4s" %% "json4s-jackson" % "4.1.1",
      "org.eclipse.jetty.ee11" % "jetty-ee11-servlet" % jettyVersion,
      "com.h2database" % "h2" % h2Version,
      "org.postgresql" % "postgresql" % postgresVersion,
      "com.zaxxer" % "HikariCP" % hikariVersion,
      "org.flywaydb" % "flyway-core" % flywayVersion,
      "org.flywaydb" % "flyway-database-postgresql" % flywayVersion % Runtime,
      "org.slf4j" % "slf4j-simple" % "2.0.20" % Runtime,
      "org.scalatra" %% "scalatra-scalatest-jakarta" % scalatraVersion % Test,
      "org.testcontainers" % "testcontainers" % testcontainersVersion % Test,
      "org.testcontainers" % "postgresql" % testcontainersVersion % Test
    )
  )

addCommandAlias("runBiller", "runMain com.example.biller.FakeBillerServer")
