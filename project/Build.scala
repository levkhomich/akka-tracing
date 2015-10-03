import sbt._
import Keys._
import com.typesafe.tools.mima.plugin.MimaKeys.previousArtifact
import com.typesafe.tools.mima.plugin.MimaPlugin.mimaDefaultSettings
import org.scoverage.coveralls.CoverallsPlugin
import org.scoverage.coveralls.CoverallsPlugin.CoverallsKeys._
import com.typesafe.sbt.SbtScalariform._
import scalariform.formatter.preferences._

object AkkaTracingBuild extends Build {

  lazy val commonSettings =
    compilationSettings ++
    testSettings ++
    publicationSettings ++
    scalariformSettings ++
    Seq (
      organization := "com.github.levkhomich",
      version := "0.5-SNAPSHOT",
      homepage := Some(url("https://github.com/levkhomich/akka-tracing")),
      startYear := Some(2014),
      licenses := Seq("Apache Public License 2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
      ScalariformKeys.preferences := ScalariformKeys.preferences.value
        .setPreference(DoubleIndentClassDeclaration, true)
        .setPreference(PreserveDanglingCloseParenthesis, true)
        .setPreference(AlignParameters, true)
    )

  lazy val compilationSettings =
    Seq(
      scalaVersion := "2.11.5",
      crossScalaVersions := Seq("2.10.4", "2.11.5"),
      javacOptions ++= Seq(
        "-Xlint:all"
      ),
      scalacOptions in GlobalScope ++= Seq(
        "-encoding", "utf8",
        "-deprecation",
        "-unchecked",
        "-feature",
        "-language:_",
        "-Xcheckinit",
        "-Xlint",
        "-Xlog-reflective-calls"
      ),
      updateOptions := updateOptions.value.withCachedResolution(true)
    )

  lazy val testSettings =
    CoverallsPlugin.projectSettings ++
    mimaDefaultSettings ++
    Seq(
      // TODO: check why %% doesn't work
      previousArtifact := Some(organization.value % (moduleName.value + '_' + scalaBinaryVersion.value) % "0.4"),
      scalacOptions in Test ++= Seq("-Yrangepos"),
      testOptions in Test := Seq(Tests.Filter(!"true".equals(System.getenv("CI")) || !_.contains("Performance")))
    )

  lazy val publicationSettings = Seq(
    publishMavenStyle := true,
    javacOptions ++= Seq(
      "-source", "1.6",
      "-target", "1.6"
    ),
    scalacOptions ++= Seq(
      "-target:jvm-1.6"
    ),
    publishTo <<= version { v =>
      val nexus = "https://oss.sonatype.org/"
      if (v.trim.endsWith("SNAPSHOT"))
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases"  at nexus + "service/local/staging/deploy/maven2")
    },
    publishArtifact in Test := false,
    pomIncludeRepository := { _ => false },
    pomExtra :=
      <scm>
        <url>https://github.com/levkhomich/akka-tracing.git</url>
        <connection>scm:git:git@github.com:levkhomich/akka-tracing.git</connection>
        <tag>HEAD</tag>
      </scm>
      <issueManagement>
        <system>github</system>
        <url>https://github.com/levkhomich/akka-tracing/issues</url>
      </issueManagement>
      <developers>
        <developer>
          <name>Lev Khomich</name>
          <email>levkhomich@gmail.com</email>
          <url>http://github.com/levkhomich</url>
        </developer>
      </developers>
  )

  lazy val root = Project(
    id = "akka-tracing-root",
    base = file("."),
    settings =
      commonSettings ++
      Seq(
        publish := (),
        publishLocal := (),
        // aggregation is performed by coverageAggregate, so we can skip regular one
        aggregate in coveralls := false,
        childCoberturaFiles := Seq.empty,
        // workaround for sbt-pgp
        packagedArtifacts := Map.empty,
        previousArtifact := None
      )
  ).aggregate(core, spray, sprayClient, play)

  val passTestDeps = "test->test;compile->compile"

  lazy val core = Project(
    id = "akka-tracing-core",
    base = file("core"),
    settings =
      commonSettings ++
      Seq(
        name := "Akka Tracing: Core",
        libraryDependencies ++=
          Dependencies.thrift ++
          Dependencies.akka ++
          Dependencies.test(scalaVersion.value),
        sourceGenerators in Compile += Def.task {
          val srcManaged = (sourceManaged in Compile).value
          val thriftSrc = (sourceDirectory in Compile).value / "thrift" / "zipkin.thrift"
          s"${baseDirectory.value}/project/gen_thrift.sh $thriftSrc $srcManaged".!
          (srcManaged / "com" / "github" / "levkhomich" / "akka" / "tracing" / "thrift").listFiles().toSeq
        }.taskValue
      )
  )

  lazy val spray = Project(
    id = "akka-tracing-spray",
    base = file("spray"),
    settings =
      commonSettings ++
      Seq(
        name := "Akka Tracing: Spray",
        libraryDependencies ++=
          Dependencies.sprayRouting(scalaVersion.value) ++
          Dependencies.test(scalaVersion.value)
      )
  ).dependsOn(core % passTestDeps)

  lazy val sprayClient = Project(
    id = "akka-tracing-spray-client",
    base = file("spray-client"),
    settings =
      commonSettings ++
      Seq(
        name := "Akka Tracing: Spray Client",
        libraryDependencies ++=
          Dependencies.sprayClient(scalaVersion.value) ++
          Dependencies.test(scalaVersion.value)
      )
  ).dependsOn(core % passTestDeps)

  lazy val play = Project(
    id = "akka-tracing-play",
    base = file("play"),
    settings =
      commonSettings ++
      Seq(
        name := "Akka Tracing: Play",
        libraryDependencies ++=
          Dependencies.play ++
          Dependencies.test(scalaVersion.value),
        previousArtifact := None,
        resolvers in GlobalScope += Resolver.typesafeRepo("releases")
      )
  ).dependsOn(core % passTestDeps)
}

object Dependencies {

  val PlayVersion = "2.3.9"
  val AkkaVersion = "2.3.12"

  object Compile {

    def sprayRouting(scalaVersion: String): ModuleID = {
      if (scalaVersion.startsWith("2.10"))
        "io.spray" % "spray-routing" % "1.3.1"
      else
        "io.spray" %% "spray-routing" % "1.3.2"
    }

    def sprayClient(scalaVersion: String): ModuleID = {
      if (scalaVersion.startsWith("2.10"))
        "io.spray" % "spray-client" % "1.3.1"
      else
        "io.spray" %% "spray-client" % "1.3.2"
    }

    val akkaActor    = "com.typesafe.akka" %% "akka-actor"          % AkkaVersion
    val akkaAgent    = "com.typesafe.akka" %% "akka-agent"          % AkkaVersion
    val akkaStream   = "com.typesafe.akka" %% "akka-stream-experimental" % "1.0"
    val play         = "com.typesafe.play" %% "play"                % PlayVersion
    val config       = "com.typesafe"      %  "config"              % "1.2.1"
    val libThrift    = "org.apache.thrift" %  "libthrift"           % "0.9.2"
  }

  object Test {

    def sprayTestkit(scalaVersion: String): ModuleID = {
      if (scalaVersion.startsWith("2.10"))
        "io.spray" % "spray-testkit" % "1.3.1" % "test"
      else
        "io.spray" %% "spray-testkit" % "1.3.2" % "test"
    }

    val specs        = "org.specs2"        %% "specs2"              % "3.0-M2"    % "test"
    val finagle      = "com.twitter"       %% "finagle-core"        % "6.28.0"    % "test"
    val playTest     = "com.typesafe.play" %% "play-test"           % PlayVersion % "test"
    val akkaTest     = "com.typesafe.akka" %% "akka-testkit"        % AkkaVersion % "test"
    val akkaRemote   = "com.typesafe.akka" %% "akka-remote"         % AkkaVersion % "test"
    val akkaSlf4j    = "com.typesafe.akka" %% "akka-slf4j"          % AkkaVersion % "test"
    val logback      = "ch.qos.logback"    % "logback-classic"      % "1.1.3"     % "test"
  }

  val akka = Seq(Compile.akkaActor, Compile.akkaAgent, Compile.akkaStream, Compile.config)
  val play = Seq(Compile.play)
  val thrift = Seq(Compile.libThrift)

  def sprayRouting(scalaVersion: String): Seq[ModuleID] =
    Seq(Compile.sprayRouting(scalaVersion))

  def sprayClient(scalaVersion: String): Seq[ModuleID] =
    Seq(Compile.sprayClient(scalaVersion))

  def test(scalaVersion: String): Seq[ModuleID] =
    Seq(Test.specs, Test.finagle, Test.playTest, Test.akkaTest,
      Test.akkaRemote, Test.sprayTestkit(scalaVersion), Test.akkaSlf4j, Test.logback)
}
