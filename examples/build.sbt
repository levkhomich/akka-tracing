name := "akka-tracing-examples"

version := "0.2"

scalaVersion := "2.10.3"

resolvers += "Maven Central Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

libraryDependencies += "com.github.levkhomich.akka.tracing" %% "akka-tracing-core" % "0.1.0-SNAPSHOT" changing()

libraryDependencies += "com.typesafe" % "config" % "1.0.2"

libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.3.0"
