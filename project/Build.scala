import sbt._
import Keys._

object AkkaTracingBuild extends Build {

  lazy val commonSettings =
    Defaults.defaultSettings ++
    Seq (
      organization := "com.github.levkhomich",
      version := "0.4-SNAPSHOT",
      scalaVersion := "2.10.4",
      homepage := Some(url("https://github.com/levkhomich/akka-tracing")),
      licenses := Seq("Apache Public License 2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0"))
    )

  lazy val compilationSettings =
//    ScoverageSbtPlugin.instrumentSettings ++
//    CoverallsPlugin.coverallsSettings ++
    Seq(
      scalacOptions in GlobalScope ++= Seq("-Xcheckinit", "-Xlint", "-deprecation", "-unchecked", "-feature", "-language:_"),
      scalacOptions in Test ++= Seq("-Yrangepos")
    )

  lazy val publicationSettings = Seq(
    publishMavenStyle := true,
    crossScalaVersions := Seq("2.10.4", "2.11.0"),
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
      <inceptionYear>2014</inceptionYear>
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
      publicationSettings ++
      Seq(
        publish := ()
      )
  ).aggregate(core, spray)

  lazy val core = Project(
    id = "akka-tracing-core",
    base = file("core"),
    settings =
      commonSettings ++
      compilationSettings ++
      publicationSettings ++ Seq(
        name := "Akka Tracing: Core",
        libraryDependencies ++=
          Dependencies.thrift ++
          Dependencies.akka ++
          Dependencies.test,
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
      compilationSettings ++
      publicationSettings ++ Seq(
        name := "Akka Tracing: Spray",
        libraryDependencies ++=
            Dependencies.spray ++
            Dependencies.test
      )
  ).dependsOn(core)
}

object Dependencies {

  object Compile {
    val akkaActor    = "com.typesafe.akka" %% "akka-actor"          % "2.3.2"
    val sprayRouting = "io.spray"          %  "spray-routing"       % "1.3.1"
    val config       = "com.typesafe"      %  "config"              % "1.2.1"
    val libThrift    = "org.apache.thrift" %  "libthrift"           % "0.9.1"
    val slf4jLog4j12 = "org.slf4j"         %  "slf4j-log4j12"       % "1.7.7"
  }

  object Test {
    val specs        = "org.specs2"        %% "specs2"              % "2.3.11" % "test"
    val finagle      = "com.twitter"       %  "finagle-core_2.10"   % "6.15.0" % "test"
    val sprayCan     = "io.spray"          %  "spray-can"           % "1.3.1"  % "test"
  }

  val akka = Seq(Compile.akkaActor, Compile.config)
  val spray = Seq(Compile.sprayRouting)
  val thrift = Seq(Compile.libThrift, Compile.slf4jLog4j12)
  val test = Seq(Test.specs, Test.finagle, Test.sprayCan)
}
