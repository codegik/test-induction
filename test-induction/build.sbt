ThisBuild / scalaVersion := "3.7.3"
ThisBuild / organization := "io.codegik"
ThisBuild / version      := "0.1.0"

lazy val root = (project in file("."))
  .settings(
    name := "test-induction",
    libraryDependencies ++= Seq(
      // WireMock is the mock engine under the hood. We use the non-standalone
      // artifact so its Jackson dependency is available on the classpath for us
      // to manipulate stub-mapping JSON before handing it to WireMock.
      "org.wiremock"   % "wiremock"        % "3.9.2",
      "ch.qos.logback" % "logback-classic" % "1.5.6",
      // Integration tests drive the running sidecar (control API + mock engine)
      // over real HTTP, so the only test dependency is the test framework itself.
      "org.scalameta"  %% "munit"          % "1.0.0" % Test
    ),
    Compile / mainClass := Some("induction.Main"),
    fork := true,
    run / connectInput := true
  )
