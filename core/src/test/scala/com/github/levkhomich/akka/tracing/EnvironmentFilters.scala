package com.github.levkhomich.akka.tracing

import org.specs2.execute.{ Skipped, SkipException, AsResult }
import org.specs2.mutable.Specification
import org.specs2.specification.Example

trait NonCIEnvironmentFilter { this: Specification with TracingTestCommons =>

  implicit def inNonCIEnvironment(s: String): InNonCIEnvironment = new InNonCIEnvironment(s)

  class InNonCIEnvironment(s: String) {
    def inNonCIEnvironment[T: AsResult](r: => T): Example = {
      def result: T =
        if (ciEnvironment)
          throw new SkipException(Skipped("- ignored in CI environment"))
        else
          r
      exampleFactory.newExample(s, result)
    }
  }

}

trait NonJava6EnvironmentFilter { this: Specification =>

  val java6Environment = System.getProperty("java.version").startsWith("1.6.")

  implicit def inNonJava6Environment(s: String): InNonJava6Environment = new InNonJava6Environment(s)

  class InNonJava6Environment(s: String) {
    def inNonJava6Environment[T: AsResult](r: => T): Example = {
      def result: T =
        if (java6Environment)
          throw new SkipException(Skipped("- ignored in Java 6 environment"))
        else
          r
      exampleFactory.newExample(s, result)
    }
  }

}
