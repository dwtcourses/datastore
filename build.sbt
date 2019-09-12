import Dependencies._
import ReleaseTransformations._

lazy val scala211 = "2.11.12"
lazy val scala212 = "2.12.9"
lazy val scala213 = "2.13.0" // Not supported yet (collections changes required in common)
lazy val supportedScalaVersions = List(scala212, scala211)

ThisBuild / organization := "org.allenai.datastore"
ThisBuild / scalaVersion := scala212

lazy val projectSettings = Seq(
  crossScalaVersions := supportedScalaVersions,
  resolvers ++= Seq(Resolver.bintrayRepo("allenai", "maven")),
  dependencyOverrides ++= Logging.loggingDependencyOverrides,
  publishMavenStyle := true,
  publishArtifact in Test := false,
  pomIncludeRepository := { _ => false },
  licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0.html")),
  homepage := Some(url("https://github.com/allenai/datastore")),
  scmInfo := Some(ScmInfo(
    url("https://github.com/allenai/datastore"),
    "https://github.com/allenai/datastore.git")),
  pomExtra := (
      <developers>
        <developer>
          <id>allenai-dev-role</id>
          <name>Allen Institute for Artificial Intelligence</name>
          <email>dev-role@allenai.org</email>
        </developer>
      </developers>),
  releasePublishArtifactsAction := PgpKeys.publishSigned.value,
  dependencyOverrides += "com.typesafe" % "config" % "1.2.1",
)

inConfig(IntegrationTest)(org.scalafmt.sbt.ScalafmtPlugin.scalafmtConfigSettings)

lazy val root = (project in file("."))
    .aggregate(
      datastore,
      cli
    )
    .configs(IntegrationTest)
    .settings(
      crossScalaVersions := Nil,
      publish / skip := true,

      /*
       * See https://www.scala-sbt.org/1.x/docs/Cross-Build.html#Note+about+sbt-release
       *
       * TLDR: sbt-release copies "+" from sbt 0.13 and the below is a workaround for using
       * sbt-release along with sbt 1.0+ and cross-compilation.
       */
      releaseCrossBuild := false,
      releaseProcess := Seq[ReleaseStep](
        checkSnapshotDependencies,
        inquireVersions,
        runClean,
        releaseStepCommandAndRemaining("+it:test"), // No non-integ tests currently
        setReleaseVersion,
        commitReleaseVersion,
        tagRelease,
        releaseStepCommandAndRemaining("+publishSigned"),
        setNextVersion,
        commitNextVersion,
        pushChanges
      )
    )

lazy val datastore = (project in file("datastore"))
    .settings(
      Defaults.itSettings,
      projectSettings
    )
    .configs(IntegrationTest)

lazy val cli = (project in file("datastore-cli"))
    .settings(
      Defaults.itSettings,
      projectSettings
    )
    .dependsOn(datastore)
    .configs(IntegrationTest)
