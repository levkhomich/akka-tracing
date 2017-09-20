import sbt._
import Keys._

import com.typesafe.tools.mima.plugin.MimaPlugin.mimaDefaultSettings
import org.scoverage.coveralls.CoverallsPlugin
import sbtdoge.CrossPerProjectPlugin

lazy val projectInfo = Seq (
  organization := "com.github.levkhomich",
  version := "0.6.1-SNAPSHOT",
  homepage := Some(url("https://github.com/levkhomich/akka-tracing")),
  scmInfo := Some(ScmInfo(
    url("https://github.com/levkhomich/akka-tracing.git"),
    "scm:git:git@github.com:levkhomich/akka-tracing.git"
  )),
  startYear := Some(2014),
  licenses := Seq("Apache Public License 2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
  developers := List(
    Developer("levkhomich", "Lev Khomich", "levkhomich@gmail.com", url("http://github.com/levkhomich"))
  )
)

lazy val root = (project in file(".")).settings(commonSettings).settings(
  publish := (),
  publishLocal := (),
  // workaround for sbt-pgp
  packagedArtifacts := Map.empty,
  mimaPreviousArtifacts := Set.empty
).aggregate(`akka-tracing-core`, `akka-tracing-play`, `akka-tracing-http`).enablePlugins(CrossPerProjectPlugin)

lazy val `akka-tracing-core` = (project in file("core")).settings(commonSettings).settings(
  libraryDependencies ++= Dependencies.thrift ++ Dependencies.akka ++ Dependencies.test(scalaVersion.value),
  sourceGenerators in Compile += Def.task {
    val srcManaged = (sourceManaged in Compile).value
    val thriftSrc = (sourceDirectory in Compile).value / "thrift" / "zipkin.thrift"
    s"${baseDirectory.value}/project/gen_thrift.sh $thriftSrc $srcManaged".!
    (srcManaged / "com" / "github" / "levkhomich" / "akka" / "tracing" / "thrift").listFiles().toSeq
  }.taskValue
)

lazy val `akka-tracing-play` = (project in file("play")).settings(commonSettings).settings(
  crossScalaVersions := Seq("2.11.11"),
  libraryDependencies ++= Dependencies.play ++ Dependencies.testPlay(scalaVersion.value),
  mimaPreviousArtifacts := Set.empty
).dependsOn(`akka-tracing-core` % passTestDeps)

lazy val `akka-tracing-http` = (project in file("akka-http")).settings(commonSettings).settings(
  libraryDependencies ++= Dependencies.http ++ Dependencies.test(scalaVersion.value)
).dependsOn(`akka-tracing-core` % passTestDeps)

lazy val commonSettings = projectInfo ++ compilationSettings ++ testSettings ++ publicationSettings

lazy val compilationSettings =
  Seq(
    scalaVersion := "2.11.11",
    crossScalaVersions := Seq("2.11.11", "2.12.3"),
    scalacOptions ++= Seq(
      "-target:jvm-1.8",
      "-encoding", "utf8",
      "-deprecation",
      "-unchecked",
      "-feature",
      "-language:_",
      "-Xlint",
      "-Xlog-reflective-calls"
    ),
    javacOptions ++= Seq(
      "-Xlint:all",
      "-source", "1.8",
      "-target", "1.8"
    ),
    updateOptions := updateOptions.value.withCachedResolution(true)
  )

lazy val testSettings =
  CoverallsPlugin.projectSettings ++
    mimaDefaultSettings ++
    Seq(
      mimaPreviousArtifacts := Set(organization.value % (moduleName.value + '_' + scalaBinaryVersion.value) % "0.4"),
      scalacOptions in Test ++= Seq("-Yrangepos")
    )

lazy val publicationSettings = Seq(
  publishMavenStyle := true,
  publishTo := {
    if (version.value.endsWith("SNAPSHOT"))
      Some(Resolver.sonatypeRepo("snapshots"))
    else
      Some("releases" at "https://oss.sonatype.org/service/local/staging/deploy/maven2")
  },
  publishArtifact in Test := false,
  pomIncludeRepository := { _ => false },
  com.typesafe.sbt.SbtScalariform.ScalariformKeys.preferences := {
    import scalariform.formatter.preferences._
    FormattingPreferences()
      .setPreference(AlignArguments, true)
      .setPreference(AlignParameters, true)
      .setPreference(DanglingCloseParenthesis, Preserve)
      .setPreference(DoubleIndentClassDeclaration, true)
      .setPreference(PreserveSpaceBeforeArguments, true)
  }
)

lazy val passTestDeps = "test->test;compile->compile"
