resolvers += Classpaths.sbtPluginReleases

addSbtPlugin("com.twitter" %% "scrooge-sbt-plugin" % "3.14.1")

addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.0.1")

addSbtPlugin("org.scoverage" % "sbt-coveralls" % "1.0.0.BETA1")

addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "0.1.6")
