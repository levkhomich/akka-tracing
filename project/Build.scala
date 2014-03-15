import sbt._
import Keys._

object AkkaTracingBuild extends Build {

  val buildSettings = Defaults.defaultSettings ++
    Seq (
      organization := "akka-tracing",
      version := "0.1.0-SNAPSHOT",
      externalResolvers := Resolver.withDefaultResolvers(Seq(
        "Typesafe releases" at "http://repo.typesafe.com/typesafe/releases/"
      )),
      scalaVersion := "2.10.3",
      scalacOptions ++= Seq("-feature", "-deprecation", "-unchecked", "-language:postfixOps")
    )

  lazy val core = Project(
    id = "akka-tracing-core",
    base = file("core"),
    settings = Defaults.defaultSettings ++ buildSettings ++ Seq(
      libraryDependencies ++=
        Dependencies.thrift ++
        Dependencies.akka ++
        Dependencies.test
    )
  )
}

object Dependencies {

  object Versions {
    val Akka = "2.3.0"
  }

  object Compile {
    val akkaActor    = "com.typesafe.akka" %% "akka-actor"    % Versions.Akka
    val akkaCluster  = "com.typesafe.akka" %% "akka-cluster"  % Versions.Akka
    val akkaContrib  = "com.typesafe.akka" %% "akka-contrib"  % Versions.Akka
    val akkaSlf4j    = "com.typesafe.akka" %% "akka-slf4j"    % Versions.Akka

    val config       = "com.typesafe"      %  "config"        % "1.0.2"
    val libThrift    = "org.apache.thrift" %  "libthrift"     % "0.9.1"
    val slf4jLog4j12 = ("org.slf4j"        %  "slf4j-log4j12" % "1.5.2")
      .exclude("javax.jms", "jms").exclude("com.sun.jdmk", "jmxtools").exclude("com.sun.jmx", "jmxri")
  }

  object Test {
    val specs        = "org.specs2"        %% "specs2"        % "2.2.3"       % "test"
    val akkaTestkit  = "com.typesafe.akka" %% "akka-testkit"  % Versions.Akka % "test"
  }

  val akka   = Seq(Compile.akkaActor, Compile.akkaCluster, Compile.akkaContrib, Compile.config)
  val thrift = Seq(Compile.libThrift, Compile.slf4jLog4j12)
  val test   = Seq(Test.specs, Test.akkaTestkit)
}
