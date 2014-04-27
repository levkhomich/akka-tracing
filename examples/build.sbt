name := "akka-tracing-examples"

version := "0.3-SNAPSHOT"

scalaVersion := "2.10.4"

resolvers += "Maven Central Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

libraryDependencies += "com.github.levkhomich" %% "akka-tracing-core" % "0.3-SNAPSHOT" changing()

libraryDependencies += "com.github.levkhomich" %% "akka-tracing-spray" % "0.3-SNAPSHOT" changing()

libraryDependencies += "io.spray" % "spray-can" % "1.3.1"

libraryDependencies += "com.typesafe" % "config" % "1.2.0"

libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.3.2"
