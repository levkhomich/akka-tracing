resolvers += Classpaths.sbtPluginReleases

addSbtPlugin("com.twitter" %% "scrooge-sbt-plugin" % "3.14.1")

addSbtPlugin("org.scoverage" % "sbt-scoverage" % "0.99.5")

addSbtPlugin("com.sksamuel.scoverage" %% "sbt-coveralls" % "0.0.5")

addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "0.1.6")
