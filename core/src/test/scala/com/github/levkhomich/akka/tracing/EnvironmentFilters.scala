package com.github.levkhomich.akka.tracing

import org.specs2.execute.{ AsResult, SkipException, Skipped }
import org.specs2.mutable.Specification
import org.specs2.specification.core.Fragment

trait NonCIEnvironmentFilter { this: Specification with TracingTestCommons =>

  implicit def inNonCIEnvironment(s: String): InNonCIEnvironment = new InNonCIEnvironment(s)

  class InNonCIEnvironment(s: String) {
    def inNonCIEnvironment[T: AsResult](r: => T): Fragment = {
      def result: T =
        if (ciEnvironment)
          throw SkipException(Skipped("- ignored in CI environment"))
        else
          r
      fragmentFactory.example(s, result)
    }
  }
}

