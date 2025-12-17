val scala3Version = "3.7.4"
val circeVersion = "0.14.15"

lazy val root = project
  .in(file("."))
  .settings(
    name := "hnsw-scala",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,
    libraryDependencies ++= Seq(
      "com.softwaremill.ox" %% "core" % "1.0.2",
      "com.softwaremill.sttp.tapir" %% "tapir-netty-server-sync" % "1.13.2",
      "io.netty" % "netty-all" % "4.2.7.Final",
      "io.cequence" %% "openai-scala-client" % "1.3.0.RC.1"
    ),
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core",
      "io.circe" %% "circe-generic"
    ).map(_ % circeVersion),
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.0" % Test,
    scalacOptions += "-Yexplicit-nulls",
    fork in run := true
  )
