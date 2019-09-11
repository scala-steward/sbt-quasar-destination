import scala.collection.Seq

performMavenCentralSync in ThisBuild := false   // basically just ignores all the sonatype sync parts of things

publishAsOSSProject in ThisBuild := true

homepage in ThisBuild := Some(url("https://github.com/slamdata/sbt-quasar-destination"))

scmInfo in ThisBuild := Some(ScmInfo(
  url("https://github.com/slamdata/sbt-quasar-destination"),
  "scm:git@github.com:slamdata/sbt-quasar-destination.git"))

lazy val root = project
  .in(file("."))
  .settings(name := "sbt-quasar-destination")
  .settings(libraryDependencies ++= Seq(
    "io.circe" %% "circe-core" % "0.12.1",
    "io.get-coursier" %% "coursier" % "2.0.0-RC2-1",
    "io.get-coursier" %% "coursier-cache" % "2.0.0-RC2-1",
    "io.get-coursier" %% "coursier-cats-interop" % "2.0.0-RC2-1"
  ))
  .enablePlugins(SbtPlugin, AutomateHeaderPlugin)
