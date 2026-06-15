ThisBuild / scalaVersion := "3.7.3"
ThisBuild / organization := "io.codegik"
ThisBuild / version      := "0.1.0"

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "test-induction-ui",
    libraryDependencies ++= Seq(
      // Only logging — the server uses the JDK's built-in HTTP server and client.
      "ch.qos.logback" % "logback-classic" % "1.5.6"
    ),
    Compile / mainClass := Some("inductionui.Main"),
    fork := true
  )
