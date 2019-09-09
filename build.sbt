import scala.collection.Seq

homepage in ThisBuild := Some(url("https://github.com/slamdata/sbt-quasar-destination"))

scmInfo in ThisBuild := Some(ScmInfo(
  url("https://github.com/slamdata/sbt-quasar-destination"),
  "scm:git@github.com:slamdata/sbt-quasar-destination.git"))

// Include to also publish a project's tests
lazy val publishTestsSettings = Seq(
  Test / packageBin / publishArtifact := true)

lazy val root = project
  .in(file("."))
  .settings(noPublishSettings)
  .aggregate(core)
  .enablePlugins(AutomateHeaderPlugin)

lazy val core = project
  .in(file("core"))
  .settings(name := "sbt-quasar-destination")
  .settings(
    performMavenCentralSync := false,
    publishAsOSSProject := true)
  .enablePlugins(AutomateHeaderPlugin)
