import sbt._

object Dependencies {

  val PlayVersion = "2.5.13"
  val AkkaVersion = "2.4.17"
  val AkkaHttpVersion = "10.0.5"

  // format: OFF
  object Compile {
    val akkaActor    = "com.typesafe.akka" %% "akka-actor"  % AkkaVersion
    val akkaAgent    = "com.typesafe.akka" %% "akka-agent"  % AkkaVersion
    val akkaStream   = "com.typesafe.akka" %% "akka-stream" % AkkaVersion
    val akkaHttp     = "com.typesafe.akka" %% "akka-http"   % AkkaHttpVersion
    val play         = "com.typesafe.play" %% "play"        % PlayVersion
    val config       = "com.typesafe"      %  "config"      % "1.3.1"
    val libThrift    = "org.apache.thrift" %  "libthrift"   % "0.10.0"
  }

  object Test {
    val specs        = "org.specs2"          %% "specs2-core"         % "3.8.9"         % "test"
    val finagle      = "com.twitter"         %% "finagle-core"        % "6.43.0"        % "test"
    val braveCore    = "io.zipkin.brave"     %  "brave-core"          % "4.0.6"         % "test"
    val playSpecs2   = "com.typesafe.play"   %% "play-specs2"         % PlayVersion     % "test"
    val akkaTest     = "com.typesafe.akka"   %% "akka-testkit"        % AkkaVersion     % "test"
    val akkaRemote   = "com.typesafe.akka"   %% "akka-remote"         % AkkaVersion     % "test"
    val akkaSlf4j    = "com.typesafe.akka"   %% "akka-slf4j"          % AkkaVersion     % "test"
    val akkaHttpTest = "com.typesafe.akka"   %% "akka-http-testkit"   % AkkaHttpVersion % "test"
    val logback      = "ch.qos.logback"      %  "logback-classic"     % "1.2.2"         % "test"
  }

  val akka = Seq(Compile.akkaActor, Compile.akkaAgent, Compile.akkaStream, Compile.config)
  val play = Seq(Compile.play)
  val http = Seq(Compile.akkaHttp)
  val thrift = Seq(Compile.libThrift)

  def test(scalaVersion: String): Seq[ModuleID] =
    Seq(Test.specs, Test.finagle, Test.braveCore, Test.akkaTest,
        Test.akkaHttpTest, Test.akkaRemote, Test.akkaSlf4j, Test.logback)

  def testPlay(scalaVersion: String): Seq[ModuleID] =
    test(scalaVersion) :+ Test.playSpecs2
}